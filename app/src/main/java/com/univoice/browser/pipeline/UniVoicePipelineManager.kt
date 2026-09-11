package com.univoice.browser.pipeline

import android.content.Context
import android.util.Log
import com.univoice.browser.config.UniVoiceConfigManager
import com.univoice.browser.model.PipelineStatus
import com.univoice.browser.model.TranslationEngineType
import com.univoice.browser.model.TtsEngineType
import com.univoice.browser.model.UniVoiceSettings
import com.univoice.browser.model.UniVoiceSubtitleCue
import com.univoice.browser.translation.CloudGeminiTranslationEngine
import com.univoice.browser.translation.LocalAiEdgeTranslationEngine
import com.univoice.browser.translation.TranslationEngine
import com.univoice.browser.tts.AndroidSystemTtsEngine
import com.univoice.browser.tts.CloudEdgeTtsEngine
import com.univoice.browser.tts.LocalOnnxTtsEngine
import com.univoice.browser.tts.TtsEngine
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * YouTube字幕 -> 翻訳 -> 音声合成 パイプラインの中核マネージャー
 * Poco F6 Pro (Snapdragon 8 Gen 2 / 12GB+ RAM) 向けに3件先読みバッファと並行パイプラインを最適化
 */
class UniVoicePipelineManager(
    private val context: Context,
    private val configManager: UniVoiceConfigManager
) {

    companion object {
        private const val TAG = "UniVoicePipelineMgr"
        private const val SLIDING_WINDOW_SIZE = 50 // 過去と未来のコンテキスト保持サイズ (12GB+ RAM向けに長大化)
    }

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "[UniVoiceBrowser] パイプライン非同期未捕捉例外: ${throwable.message}", throwable)
        _errorMessage.value = "パイプライン復旧処理を実行しました: ${throwable.localizedMessage}"
    }

    private val pipelineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + exceptionHandler)

    // エンジンインスタンス
    private var translationEngine: TranslationEngine? = null
    private var ttsEngine: TtsEngine? = null

    // 動画再生中フラグ
    @Volatile
    var isVideoPlaying: Boolean = true

    // 状態管理 Flow
    private val _currentStatus = MutableStateFlow(PipelineStatus.IDLE)
    val currentStatus: StateFlow<PipelineStatus> = _currentStatus.asStateFlow()

    private val _currentCue = MutableStateFlow<UniVoiceSubtitleCue?>(null)
    val currentCue: StateFlow<UniVoiceSubtitleCue?> = _currentCue.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _captionGuidanceMessage = MutableStateFlow<String?>(null)
    val captionGuidanceMessage: StateFlow<String?> = _captionGuidanceMessage.asStateFlow()

    private val _prefetchedCountFlow = MutableStateFlow(0)
    val prefetchedCountFlow: StateFlow<Int> = _prefetchedCountFlow.asStateFlow()

    // 予備用システムTTS & クラウドTTS (モデル未配置またはAPIエラー時の即時フォールバック)
    private val fallbackSystemTts = com.univoice.browser.tts.AndroidSystemTtsEngine(context)
    private val cloudEdgeTts = com.univoice.browser.tts.CloudEdgeTtsEngine(context)
    private val fallbackLocalTranslation = com.univoice.browser.translation.LocalAiEdgeTranslationEngine(context)
    private val freeWebTranslation = com.univoice.browser.translation.FreeWebTranslationEngine()

    // スライディングウィンドウ・キャッシュ (12GB+ RAM最適化, LRU自動破棄付き)
    private val slidingWindowQueue = ConcurrentLinkedQueue<UniVoiceSubtitleCue>()
    private val MAX_TRANSLATION_CACHE_SIZE = 1000
    private val translationCache = ConcurrentHashMap<String, String>() // cleanText -> translatedText
    private val cueChannel = Channel<UniVoiceSubtitleCue>(Channel.BUFFERED)

    private fun putTranslationCache(key: String, value: String) {
        if (translationCache.size >= MAX_TRANSLATION_CACHE_SIZE) {
            // 最古のエントリを間引いてサイズを一定に保持
            val keysToRemove = translationCache.keys().toList().take(200)
            keysToRemove.forEach { translationCache.remove(it) }
        }
        translationCache[key] = value
    }

    private val thermalManager = com.univoice.browser.hardware.UniVoiceThermalManager(context)
    private val transcriptRepo = com.univoice.browser.transcript.TranscriptRepository.getInstance(context)
    private var workerJob: Job? = null

    init {
        // フォールバック用TTSの事前ウォームアップ
        pipelineScope.launch(Dispatchers.Main) {
            try {
                fallbackSystemTts.initialize()
                cloudEdgeTts.initialize()
            } catch (e: Exception) {
                Log.w(TAG, "[UniVoiceBrowser] フォールバックTTS初期化待機: ${e.message}")
            }
        }

        // 設定変更の監視と動的再初期化
        pipelineScope.launch {
            configManager.settingsFlow.collect { settings ->
                reconfigureEngines(settings)
            }
        }

        // サーマルマネジメント（発熱による自動負荷保護）
        pipelineScope.launch {
            thermalManager.thermalWarningMessage.collect { warning ->
                if (warning != null) {
                    _errorMessage.value = warning
                }
            }
        }

        startProcessingLoop()
    }

    /**
     * 現在の設定に基づいて翻訳エンジンおよびTTSエンジンを再構築
     */
    private suspend fun reconfigureEngines(settings: UniVoiceSettings) = withContext(Dispatchers.Default) {
        Log.i(TAG, "[UniVoiceBrowser] パイプラインエンジンを再構成中: モード=${settings.currentMode.titleJapanese}")

        // 翻訳エンジンの選定
        val targetTransType = settings.effectiveTranslationEngine
        translationEngine?.release()
        translationEngine = when (targetTransType) {
            TranslationEngineType.GEMINI_CLOUD -> {
                if (settings.geminiApiKey.isNotBlank()) {
                    CloudGeminiTranslationEngine(
                        apiKey = settings.geminiApiKey,
                        modelName = settings.geminiModelName,
                        customEndpoint = settings.customEndpointUrl
                    )
                } else {
                    Log.i(TAG, "[UniVoiceBrowser] Gemini APIキー未入力のため、高精度Web翻訳エンジンを直接適用します")
                    freeWebTranslation
                }
            }
            TranslationEngineType.LOCAL_EDGE -> LocalAiEdgeTranslationEngine(
                context = context,
                hardwareAcceleration = settings.hardwareAcceleration
            )
        }
        translationEngine?.initialize()
        Log.i(TAG, "[UniVoiceBrowser] 翻訳エンジンを初期化: ${targetTransType.titleJapanese}")

        // TTSエンジンの選定
        val targetTtsType = settings.effectiveTtsEngine
        if (ttsEngine !== fallbackSystemTts && ttsEngine !== cloudEdgeTts) {
            ttsEngine?.release()
        }
        ttsEngine = when (targetTtsType) {
            TtsEngineType.LOCAL_VOICEVOX_ONNX -> {
                val modelMgr = com.univoice.browser.modelmgr.ModelDownloadManager.getInstance(context)
                if (modelMgr.isModelInstalled(com.univoice.browser.modelmgr.ModelDownloadManager.MODEL_VOICEVOX)) {
                    LocalOnnxTtsEngine(
                        context = context,
                        hardwareAcceleration = settings.hardwareAcceleration
                    )
                } else {
                    Log.i(TAG, "[UniVoiceBrowser] VOICEVOXモデル未配備のため、高音質クラウドTTSエンジンで即時再生します")
                    cloudEdgeTts
                }
            }
            TtsEngineType.CLOUD_EDGE_TTS -> cloudEdgeTts
            TtsEngineType.ANDROID_SYSTEM -> fallbackSystemTts
        }
        ttsEngine?.initialize()
        Log.i(TAG, "[UniVoiceBrowser] 音声合成エンジンを初期化: ${targetTtsType.titleJapanese}")
    }

    /**
     * キュー処理ワーカーを開始
     */
    private fun startProcessingLoop() {
        workerJob?.cancel()
        workerJob = pipelineScope.launch {
            for (cue in cueChannel) {
                processSubtitleCue(cue)
            }
        }
    }

    private var lastReceivedText = ""
    private var lastReceivedTime = 0L

    /**
     * JSInterfaceから字幕を受信したときのエントリポイント
     */
    fun onSubtitleReceived(cue: UniVoiceSubtitleCue) {
        val clean = cue.cleanText
        if (clean.isBlank() || clean.length < 2) return

        val now = System.currentTimeMillis()
        // 同一テキストの連打を抑止（2秒以内）
        if (clean == lastReceivedText && (now - lastReceivedTime) < 2000L) {
            return
        }
        lastReceivedText = clean
        lastReceivedTime = now

        _captionGuidanceMessage.value = null // 字幕受信成功によりガイダンスを解除
        _currentStatus.value = PipelineStatus.INTERCEPTING
        // スライディングウィンドウに追加
        slidingWindowQueue.add(cue)
        while (slidingWindowQueue.size > SLIDING_WINDOW_SIZE) {
            slidingWindowQueue.poll()
        }

        // チャンネルへ投入
        cueChannel.trySend(cue)

        // 後続の未翻訳字幕を直ちにバックグラウンド先読み開始
        prefetchUpcomingCues()
    }

    /**
     * 字幕(CC)の有効化状態変更の通知
     * ※ バッチダウンロード翻訳モード時は音声からAIが直接文字起こしするため、警告を出さない
     */
    fun onCaptionStateChanged(isEnabled: Boolean) {
        val isBatchMode = configManager.currentSettings.executionStyle == com.univoice.browser.model.ExecutionStyle.BATCH_DOWNLOAD
        if (!isEnabled && !isBatchMode) {
            _captionGuidanceMessage.value = "⚠️ 字幕(CC)がオフです。動画をタップして右上の[CC]を押してください"
        } else {
            _captionGuidanceMessage.value = null
        }
    }

    /**
     * ガイダンス・エラー通知の手動消去（「閉じる」タップ時）
     */
    fun dismissGuidance() {
        _captionGuidanceMessage.value = null
        _errorMessage.value = null
    }

    /**
     * 単一字幕キューのパイプライン実行 (先読みキャッシュ確認 -> 翻訳 -> 音声合成再生)
     */
    private suspend fun processSubtitleCue(cue: UniVoiceSubtitleCue) {
        val cleanText = cue.cleanText.trim().trimStart('.', ',', ':', ';', '!', '?', '-', ' ').trim()
        if (cleanText.isBlank()) return

        val settings = configManager.currentSettings

        // 1. すでに日本語字幕の場合は翻訳処理をバイパスして即座に利用
        val isAlreadyJapanese = cleanText.any { it.code in 0x3040..0x30FF || it.code in 0x4E00..0x9FFF }
        var translated: String = if (isAlreadyJapanese) {
            cleanText
        } else {
            translationCache[cleanText] ?: ""
        }

        if (translated.isBlank()) {
            _currentStatus.value = PipelineStatus.TRANSLATING
            val history = slidingWindowQueue.map { it.cleanText }

            val transEngine = translationEngine ?: freeWebTranslation
            val result = transEngine.translate(cleanText, history)

            translated = result.getOrElse { error ->
                Log.w(TAG, "[UniVoiceBrowser] 主系翻訳警告: ${error.message}。Web無料翻訳を実行")
                val webRes = freeWebTranslation.translate(cleanText, history)
                webRes.getOrElse {
                    fallbackLocalTranslation.translate(cleanText, history).getOrDefault("")
                }
            }

            if (translated.isNotBlank()) {
                putTranslationCache(cleanText, translated)
            }
        } else {
            Log.d(TAG, "[UniVoiceBrowser] キャッシュまたは直接日本語ヒット: [$cleanText] -> [$translated]")
        }

        if (translated.isBlank()) {
            Log.w(TAG, "[UniVoiceBrowser] 日本語訳が取得できなかったため再生スキップ: $cleanText")
            _currentStatus.value = PipelineStatus.IDLE
            return
        }

        cue.translatedText = translated
        _currentCue.value = cue
        _prefetchedCountFlow.value = translationCache.size

        // 日英対訳スクリプト履歴へ自動記録 (語学学習用)
        transcriptRepo.addCue(cue)

        // 経過時間が長すぎる場合（リアルタイム動画再生中に大きく遅延した場合のみスキップ）
        // 一時停止中の場合は動画停止中なのでスキップ判定を行わない
        val cueAgeMs = System.currentTimeMillis() - cue.createdAt
        if (isVideoPlaying && cueAgeMs > 15000L) {
            Log.d(TAG, "[UniVoiceBrowser] 時間経過したキューのため音声合成をスキップ (${cueAgeMs}ms経過): $cleanText")
            _currentStatus.value = PipelineStatus.IDLE
            return
        }

        // 端末のメディア音量確認（消音時はユーザーへガイダンス）
        try {
            val audioManager = context.getSystemService(android.content.Context.AUDIO_SERVICE) as? android.media.AudioManager
            val currentVol = audioManager?.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) ?: 10
            if (currentVol == 0) {
                _errorMessage.value = "⚠️ 音声が消音(音量0)です。端末の音量ボタンで上げてください"
            }
        } catch (_: Exception) {}

        // 動画が一時停止中の場合は音声発話を待機（翻訳キャッシュのみ完了させておく）
        if (!isVideoPlaying) {
            Log.d(TAG, "[UniVoiceBrowser] 動画一時停止中のため発話は待機し、翻訳キャッシュのみ完了: $cleanText -> $translated")
            prefetchUpcomingCues()
            _currentStatus.value = PipelineStatus.IDLE
            return
        }

        // 2. 音声合成および再生 (動的リップシンク・タイムストレッチ適用)
        _currentStatus.value = PipelineStatus.SYNTHESIZING
        val tts = ttsEngine ?: cloudEdgeTts

        // 字幕表示時間に合わせて発話速度をリアルタイムに自動微調整
        val dynamicSpeed = DynamicTimeStretcher.calculateOptimalSpeed(
            translatedJapanese = translated,
            availableDurationMs = cue.durationMs,
            baseSpeed = settings.speechSpeed
        )

        _currentStatus.value = PipelineStatus.PLAYING
        val ttsResult = tts.synthesizeAndPlay(
            text = translated,
            speed = dynamicSpeed,
            pitch = settings.speechPitch
        )

        ttsResult.onFailure { error ->
            Log.w(TAG, "[UniVoiceBrowser] 主系TTSエンジン警告: ${error.message}。高音質クラウドTTSへフォールバック")
            val cloudRes = cloudEdgeTts.synthesizeAndPlay(
                text = translated,
                speed = dynamicSpeed,
                pitch = settings.speechPitch
            )
            cloudRes.onFailure {
                fallbackSystemTts.synthesizeAndPlay(
                    text = translated,
                    speed = dynamicSpeed,
                    pitch = settings.speechPitch
                )
            }
        }

        // 3. 次の字幕の長大先読み (Prefetch up to 20 cues ahead)
        prefetchUpcomingCues()

        _currentStatus.value = PipelineStatus.IDLE
    }

    /**
     * 未翻訳の先読み候補を非同期に事前翻訳（最大20件まで先読み実行）
     */
    fun prefetchUpcomingCues() {
        val pendingList = slidingWindowQueue.filter { it.translatedText == null && !translationCache.containsKey(it.cleanText) }
        if (pendingList.isEmpty()) return

        pipelineScope.launch(Dispatchers.IO) {
            for (pending in pendingList.take(20)) {
                val clean = pending.cleanText
                if (clean.isNotBlank() && !translationCache.containsKey(clean)) {
                    val isJp = clean.any { it.code in 0x3040..0x30FF || it.code in 0x4E00..0x9FFF }
                    if (isJp) {
                        putTranslationCache(clean, clean)
                        pending.translatedText = clean
                        _prefetchedCountFlow.value = translationCache.size
                        continue
                    }
                    val history = slidingWindowQueue.map { it.cleanText }
                    val trans = (translationEngine ?: freeWebTranslation).translate(clean, history).getOrNull()
                    if (!trans.isNullOrBlank()) {
                        putTranslationCache(clean, trans)
                        pending.translatedText = trans
                        _prefetchedCountFlow.value = translationCache.size
                        Log.d(TAG, "[UniVoiceBrowser] 先読みバッファ完了 (${translationCache.size}件保持): [$clean] -> [$trans]")
                    }
                }
            }
        }
    }

    /**
     * エラーおよびガイダンスオーバーレイのクリア
     */
    fun clearError() {
        _errorMessage.value = null
        _captionGuidanceMessage.value = null
    }

    /**
     * 一時停止時の音声出力のみ停止（先読みキューや翻訳キャッシュは完全に維持）
     */
    fun pauseAudioOutputOnly() {
        isVideoPlaying = false
        if (ttsEngine !== fallbackSystemTts && ttsEngine !== cloudEdgeTts) {
            ttsEngine?.stop()
        }
        cloudEdgeTts.stop()
        fallbackSystemTts.stop()
        if (_currentStatus.value == PipelineStatus.PLAYING || _currentStatus.value == PipelineStatus.SYNTHESIZING) {
            _currentStatus.value = PipelineStatus.IDLE
        }
        // 一時停止中にも先読み翻訳を積極的にトリガー
        prefetchUpcomingCues()
    }

    /**
     * 再生再開時の通知
     */
    fun resumeAudioOutput() {
        isVideoPlaying = true
    }

    /**
     * 再生停止（完全停止）
     */
    fun stopAudio() {
        isVideoPlaying = false
        while (cueChannel.tryReceive().isSuccess) {}
        if (ttsEngine !== fallbackSystemTts && ttsEngine !== cloudEdgeTts) {
            ttsEngine?.stop()
        }
        cloudEdgeTts.stop()
        fallbackSystemTts.stop()
        _currentStatus.value = PipelineStatus.IDLE
    }

    /**
     * リソース解放
     */
    fun release() {
        workerJob?.cancel()
        cueChannel.close()
        translationEngine?.release()
        if (ttsEngine !== fallbackSystemTts && ttsEngine !== cloudEdgeTts) {
            ttsEngine?.release()
        }
        cloudEdgeTts.release()
        fallbackSystemTts.release()
        fallbackLocalTranslation.release()
        freeWebTranslation.release()
        translationCache.clear()
        slidingWindowQueue.clear()
        thermalManager.release()
        Log.i(TAG, "[UniVoiceBrowser] パイプラインマネージャーのリソースを完全に解放しました")
    }
}

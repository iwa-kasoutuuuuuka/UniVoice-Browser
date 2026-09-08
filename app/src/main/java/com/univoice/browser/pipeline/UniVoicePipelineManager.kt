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
        private const val SLIDING_WINDOW_SIZE = 6 // 過去と未来のコンテキスト保持サイズ
    }

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "[UniVoiceBrowser] パイプライン非同期未捕捉例外: ${throwable.message}", throwable)
        _errorMessage.value = "パイプライン復旧処理を実行しました: ${throwable.localizedMessage}"
    }

    private val pipelineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + exceptionHandler)

    // エンジンインスタンス
    private var translationEngine: TranslationEngine? = null
    private var ttsEngine: TtsEngine? = null

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

    // 予備用システムTTS (モデル未配置またはAPIエラー時の即時フォールバック)
    private val fallbackSystemTts = com.univoice.browser.tts.AndroidSystemTtsEngine(context)
    private val fallbackLocalTranslation = com.univoice.browser.translation.LocalAiEdgeTranslationEngine(context)
    private val freeWebTranslation = com.univoice.browser.translation.FreeWebTranslationEngine()

    // スライディングウィンドウ・キャッシュ (12GB+ RAM最適化)
    private val slidingWindowQueue = ConcurrentLinkedQueue<UniVoiceSubtitleCue>()
    private val translationCache = ConcurrentHashMap<String, String>() // cleanText -> translatedText
    private val cueChannel = Channel<UniVoiceSubtitleCue>(Channel.BUFFERED)

    private val thermalManager = com.univoice.browser.hardware.UniVoiceThermalManager(context)
    private val transcriptRepo = com.univoice.browser.transcript.TranscriptRepository.getInstance(context)
    private var workerJob: Job? = null

    init {
        // フォールバック用システムTTSの事前ウォームアップ
        pipelineScope.launch(Dispatchers.Main) {
            try {
                fallbackSystemTts.initialize()
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
        ttsEngine?.release()
        ttsEngine = when (targetTtsType) {
            TtsEngineType.LOCAL_VOICEVOX_ONNX -> {
                val modelMgr = com.univoice.browser.modelmgr.ModelDownloadManager.getInstance(context)
                if (modelMgr.isModelInstalled(com.univoice.browser.modelmgr.ModelDownloadManager.MODEL_VOICEVOX)) {
                    LocalOnnxTtsEngine(
                        context = context,
                        hardwareAcceleration = settings.hardwareAcceleration
                    )
                } else {
                    Log.i(TAG, "[UniVoiceBrowser] VOICEVOXモデル未配備のため、標準TTSエンジンで即時再生します")
                    fallbackSystemTts
                }
            }
            TtsEngineType.CLOUD_EDGE_TTS -> CloudEdgeTtsEngine(context = context)
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

    /**
     * JSInterfaceから字幕を受信したときのエントリポイント
     */
    fun onSubtitleReceived(cue: UniVoiceSubtitleCue) {
        _captionGuidanceMessage.value = null // 字幕受信成功によりガイダンスを解除
        _currentStatus.value = PipelineStatus.INTERCEPTING
        // スライディングウィンドウに追加
        slidingWindowQueue.add(cue)
        while (slidingWindowQueue.size > SLIDING_WINDOW_SIZE) {
            slidingWindowQueue.poll()
        }

        // チャンネルへ投入
        cueChannel.trySend(cue)
    }

    /**
     * 字幕(CC)の有効化状態変更の通知
     */
    fun onCaptionStateChanged(isEnabled: Boolean) {
        if (!isEnabled) {
            _captionGuidanceMessage.value = "⚠️ 字幕(CC)がオフです。動画をタップして右上の[CC]を押してください"
        } else {
            _captionGuidanceMessage.value = null
        }
    }

    /**
     * 単一字幕キューのパイプライン実行 (先読みキャッシュ確認 -> 翻訳 -> 音声合成再生)
     */
    private suspend fun processSubtitleCue(cue: UniVoiceSubtitleCue) {
        val cleanText = cue.cleanText
        if (cleanText.isBlank()) return

        val settings = configManager.currentSettings

        var translated = translationCache[cleanText]
        if (translated == null) {
            _currentStatus.value = PipelineStatus.TRANSLATING
            val history = slidingWindowQueue.map { it.cleanText }

            val transEngine = translationEngine ?: freeWebTranslation
            val result = transEngine.translate(cleanText, history)

            translated = result.getOrElse { error ->
                Log.w(TAG, "[UniVoiceBrowser] 翻訳エンジン警告: ${error.message}。Web翻訳/ローカル辞書フォールバックを実行")
                val webRes = freeWebTranslation.translate(cleanText, history)
                webRes.getOrElse {
                    fallbackLocalTranslation.translate(cleanText, history).getOrDefault("動画の音声: $cleanText")
                }
            }
            translationCache[cleanText] = translated
        } else {
            Log.d(TAG, "[UniVoiceBrowser] 先読みキャッシュヒット: [$cleanText] -> [$translated]")
        }

        cue.translatedText = translated
        _currentCue.value = cue
        _prefetchedCountFlow.value = translationCache.size

        // 日英対訳スクリプト履歴へ自動記録 (語学学習用)
        transcriptRepo.addCue(cue)

        // 端末のメディア音量確認（消音時はユーザーへガイダンス）
        try {
            val audioManager = context.getSystemService(android.content.Context.AUDIO_SERVICE) as? android.media.AudioManager
            val currentVol = audioManager?.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) ?: 10
            if (currentVol == 0) {
                _errorMessage.value = "⚠️ 音声が消音(音量0)です。端末の音量ボタンで上げてください"
            }
        } catch (_: Exception) {}

        // 2. 音声合成および再生 (動的リップシンク・タイムストレッチ適用)
        _currentStatus.value = PipelineStatus.SYNTHESIZING
        val tts = ttsEngine ?: fallbackSystemTts

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
            Log.w(TAG, "[UniVoiceBrowser] 音声合成エンジンエラー: ${error.message}。標準システムTTSで即時再生", error)
            // モデル未配置等の場合は端末の標準日本語TTSで確実に音声を鳴らす
            fallbackSystemTts.synthesizeAndPlay(
                text = translated,
                speed = dynamicSpeed,
                pitch = settings.speechPitch
            )
        }

        // 3. 次の字幕の先読み (Prefetch 3 cues ahead)
        prefetchUpcomingCues()
    }

    /**
     * 3件先読みバッファのバックグラウンド実行
     */
    private fun prefetchUpcomingCues() {
        val settings = configManager.currentSettings
        if (settings.prefetchCount <= 0) return

        pipelineScope.launch(Dispatchers.IO) {
            // スライディングウィンドウ内で未翻訳のものを先読み
            val pendingCues = slidingWindowQueue.filter {
                !translationCache.containsKey(it.cleanText)
            }.take(settings.prefetchCount)

            for (pending in pendingCues) {
                try {
                    val result = translationEngine?.translate(pending.cleanText)
                    result?.onSuccess { trans ->
                        translationCache[pending.cleanText] = trans
                        pending.translatedText = trans
                        pending.isPrefetched = true
                        _prefetchedCountFlow.value = translationCache.size
                        Log.d(TAG, "[UniVoiceBrowser] 先読み翻訳完了: [${pending.cleanText}] -> [$trans]")
                    }
                } catch (e: Exception) {
                    Log.v(TAG, "[UniVoiceBrowser] 先読みスキップ: ${e.message}")
                }
            }
        }
    }

    /**
     * エラーオーバーレイのクリア
     */
    fun clearError() {
        _errorMessage.value = null
    }

    /**
     * 再生停止
     */
    fun stopAudio() {
        ttsEngine?.stop()
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
        ttsEngine?.release()
        fallbackSystemTts.release()
        fallbackLocalTranslation.release()
        translationCache.clear()
        slidingWindowQueue.clear()
        thermalManager.release()
        Log.i(TAG, "[UniVoiceBrowser] パイプラインマネージャーのリソースを完全に解放しました")
    }
}

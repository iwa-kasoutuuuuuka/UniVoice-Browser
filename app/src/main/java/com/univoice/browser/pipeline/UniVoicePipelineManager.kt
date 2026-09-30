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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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
        private const val MAX_CONTEXT_PAIRS = 6
        private val VIDEO_ID_PARAM = Regex("[?&]v=([^&]+)")

        /** 字幕開始の少し前から読み上げを始め、TTSの起動遅延を吸収 */
        private const val TIMED_LEAD_MS = 150L
        /** 終了時刻をこれ以上過ぎた字幕トラック行は読み上げずに破棄 */
        private const val TIMED_STALE_TOLERANCE_MS = 1000L
        /** 再生位置よりこれ以上先の行は（シーク直後など）待たずに破棄し、後続を塞がない */
        private const val TIMED_MAX_AHEAD_MS = 180_000L
        private const val TIMED_MAX_POLL_MS = 250L
        private const val TIMED_IDLE_POLL_MS = 200L
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
    private val slidingWindowSize = java.util.concurrent.atomic.AtomicInteger(0)
    private val MAX_TRANSLATION_CACHE_SIZE = 1000
    private val translationCache = ConcurrentHashMap<String, String>() // cleanText -> translatedText
    private val cueChannel = Channel<UniVoiceSubtitleCue>(Channel.BUFFERED)

    private fun putTranslationCache(key: String, value: String) {
        synchronized(translationCache) {
            if (translationCache.size >= MAX_TRANSLATION_CACHE_SIZE) {
                // 最古のエントリを間引いてサイズを一定に保持
                val keysToRemove = translationCache.keys().toList().take(200)
                keysToRemove.forEach { translationCache.remove(it) }
            }
            translationCache[key] = value
        }
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
        cloudEdgeTts.voiceGender = settings.voiceGender
        fallbackSystemTts.voiceGender = settings.voiceGender

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
        Log.i(TAG, "[UniVoiceBrowser] 音声合成エンジンを初期化: ${targetTtsType.titleJapanese} (性別: ${settings.voiceGender.titleJapanese})")
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

    private val sentenceMerger = CaptionSentenceMerger()
    private var mergeFlushJob: Job? = null

    // 字幕トラック由来の字幕を動画の再生位置に合わせて読み上げるための状態
    private val playbackClock = PlaybackClock()
    private val timedCueRegistry = TimedCueRegistry()
    private val seekGeneration = java.util.concurrent.atomic.AtomicInteger(0)
    private val cueSeekGeneration = ConcurrentHashMap<String, Int>()
    private val trackFeeder = CaptionTrackFeeder()
    @Volatile
    private var currentVideoId: String? = null

    // 翻訳の重複実行防止（先読みと本処理で同じ原文を二重にAPIへ送らない）
    private val inFlightTranslations = ConcurrentHashMap<String, kotlinx.coroutines.Deferred<Result<String>>>()
    private val prefetchRequested = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile
    private var prefetchJob: Job? = null

    /** 動画の再生状態・再生位置の通知（JS timeupdate / play / pause） */
    fun onPlaybackPositionChanged(isPlaying: Boolean, positionMs: Long) {
        if (playbackClock.update(isPlaying, positionMs)) {
            onSeekDetected(positionMs)
        }
        feedTrackCues()
    }

    /**
     * プレーヤーが取得した字幕トラック本文（傍受した timedtext）を受け取り、再生位置に合わせた読み上げの元データにする
     * ※ playerResponse の字幕URLを直接取得すると空応答になるため、プレーヤー自身の取得結果を使う
     */
    fun onCaptionTrackCaptured(url: String?, body: String) {
        pipelineScope.launch {
            val segments = if (body.trimStart().startsWith("{")) {
                com.univoice.browser.batch.YouTubeTimedTextParser.parseJson3(body)
            } else {
                com.univoice.browser.batch.YouTubeTimedTextParser.parseXml(body)
            }
            if (segments.isEmpty()) return@launch
            val trackVideoId = url?.let { VIDEO_ID_PARAM.find(it)?.groupValues?.get(1) } ?: currentVideoId
            val cues = segments.map { CaptionTrackFeeder.TrackCue(it.startMs, it.endMs, it.originalText) }

            withContext(Dispatchers.Main) {
                val position = if (playbackClock.hasPosition) playbackClock.positionMs() else 0L
                val (changed, replacedOtherTrack) = synchronized(trackFeeder) {
                    val hadTrackForSameVideo = trackFeeder.hasTrack && trackFeeder.videoId == trackVideoId
                    trackFeeder.setTrack(trackVideoId, "${body.length}:${body.hashCode()}", cues, position) to hadTrackForSameVideo
                }
                if (!changed) return@withContext
                Log.i(TAG, "[UniVoiceBrowser] 字幕トラックを取得: ${cues.size}行 (動画=$trackVideoId)。再生位置に合わせて読み上げます")
                // 字幕言語の切替等で別トラックになった場合は、旧トラックの読み上げ待ち行を破棄
                if (replacedOtherTrack) invalidatePendingTimedCues()
                feedTrackCues()
            }
        }
    }

    /** 再生位置の先読み範囲に入った字幕トラック行を、読み上げ待ちとして投入（メインスレッドから呼ぶ） */
    private fun feedTrackCues() {
        if (!playbackClock.hasPosition) return
        val due = synchronized(trackFeeder) {
            val videoId = currentVideoId
            if (!trackFeeder.hasTrack || (videoId != null && trackFeeder.videoId != videoId)) return
            trackFeeder.takeDue(playbackClock.positionMs())
        }
        due.forEach { onSubtitleReceived(UniVoiceSubtitleCue(startTimeMs = it.startMs, endTimeMs = it.endMs, originalText = it.text, isTimed = true)) }
    }

    fun onPlaybackRateChanged(rate: Float) {
        playbackClock.setRate(rate)
    }

    private fun onSeekDetected(positionMs: Long) {
        Log.i(TAG, "[UniVoiceBrowser] シークを検知 (${positionMs}ms): 再生位置待ちの字幕トラック行を破棄し、新しい再生位置から再投入します")
        invalidatePendingTimedCues()
        stopTtsPlayback()
    }

    /**
     * 再生位置待ちの字幕トラック行（結合待ち・キュー内・待機中）をすべて破棄する
     * 字幕トラックを保持していれば現在位置から再投入し、無ければ画面字幕での読み上げに委ねる
     */
    private fun invalidatePendingTimedCues() {
        seekGeneration.incrementAndGet()
        timedCueRegistry.dropAllPending()
        mergeFlushJob?.cancel()
        synchronized(sentenceMerger) { sentenceMerger.reset() }
        // 破棄した行を字幕トラックから再投入できるよう、払い出し位置を現在の再生位置へ合わせる
        if (playbackClock.hasPosition) {
            val position = playbackClock.positionMs()
            synchronized(trackFeeder) { trackFeeder.seekTo(position) }
        }
    }

    /**
     * 字幕トラック由来の字幕について、再生位置が開始時刻に達するまで待機
     * 再生位置が未取得の間（自動再生オフ等）も待機し、一括受信した行を即時に連続読み上げしない
     * @return 読み上げるべき場合 true、シーク・経過・大きく先の字幕のため破棄すべき場合 false
     */
    private suspend fun awaitTimedCueDue(cue: UniVoiceSubtitleCue, generation: Int): Boolean {
        while (true) {
            if (generation != seekGeneration.get()) return false
            if (!playbackClock.hasPosition) {
                kotlinx.coroutines.delay(TIMED_IDLE_POLL_MS)
                continue
            }
            val pos = playbackClock.positionMs()
            if (pos > cue.endTimeMs + TIMED_STALE_TOLERANCE_MS) return false
            if (pos < cue.startTimeMs - TIMED_MAX_AHEAD_MS) return false
            val playing = playbackClock.isPlaying && isVideoPlaying
            val remainingMs = cue.startTimeMs - TIMED_LEAD_MS - pos
            if (playing && remainingMs <= 0) return true
            // 再生中は開始時刻までの実時間だけ待つ（シーク検知のため上限あり）、停止中は低頻度で確認
            val waitMs = if (playing) (remainingMs / playbackClock.playbackRate).toLong().coerceIn(20L, TIMED_MAX_POLL_MS) else TIMED_IDLE_POLL_MS
            kotlinx.coroutines.delay(waitMs)
        }
    }

    private fun isTimedCueStale(cue: UniVoiceSubtitleCue, generation: Int): Boolean =
        generation != seekGeneration.get() ||
            (playbackClock.hasPosition && playbackClock.positionMs() > cue.endTimeMs + TIMED_STALE_TOLERANCE_MS)

    // 直前に確定した (原文, 訳文)。文体・訳語の一貫性のため翻訳プロンプトへ渡す
    private val recentPairs = ArrayDeque<Pair<String, String>>()

    @Volatile
    private var videoTitle: String? = null

    /** 動画タイトルを更新（翻訳時の話題・固有名詞の手がかり） */
    fun updateVideoTitle(title: String?) {
        videoTitle = title?.removeSuffix(" - YouTube")?.trim()?.takeIf { it.isNotBlank() && it != "YouTube" }
    }

    private fun currentTranslationContext(): com.univoice.browser.translation.TranslationContext =
        com.univoice.browser.translation.TranslationContext(
            previousPairs = synchronized(recentPairs) { recentPairs.toList() },
            videoTitle = videoTitle
        )

    private fun rememberPair(source: String, translated: String) {
        synchronized(recentPairs) {
            recentPairs.addLast(source to translated)
            while (recentPairs.size > MAX_CONTEXT_PAIRS) recentPairs.removeFirst()
        }
    }

    /**
     * JSInterfaceから字幕を受信したときのエントリポイント
     */
    fun onSubtitleReceived(cue: UniVoiceSubtitleCue) {
        val clean = cue.cleanText
        if (clean.isBlank() || clean.length < 2) return

        if (!cue.isTimed && timedCueRegistry.isCoveredByTrack(cue.startTimeMs)) {
            // 字幕トラックから先読み済みの区間は、画面字幕(DOM)から拾った同じ行を二重に読み上げない
            Log.d(TAG, "[UniVoiceBrowser] 字幕トラックで処理済み・予定の区間のため画面字幕を抑止: $clean")
            return
        }

        // 同一テキストの連打を抑止（2秒以内）。画面字幕のちらつき対策のため、実タイムスタンプを持つトラック行
        // （歌詞の繰り返し等、同じ文が続くことがある）には適用しない
        if (!cue.isTimed) {
            val now = System.currentTimeMillis()
            if (clean == lastReceivedText && (now - lastReceivedTime) < 2000L) {
                return
            }
            lastReceivedText = clean
            lastReceivedTime = now
        }

        // 実際に処理する行だけを読み上げ予定として登録（破棄した行が画面字幕を抑止し続けないように）
        if (cue.isTimed) timedCueRegistry.registerPending(cue.startTimeMs, cue.endTimeMs)

        _captionGuidanceMessage.value = null // 字幕受信成功によりガイダンスを解除
        _currentStatus.value = PipelineStatus.INTERCEPTING

        // 文の途中で区切られた断片を文単位に結合してから翻訳へ回す（断片翻訳による誤訳を防止）
        val (ready, flushDelayMs) = synchronized(sentenceMerger) {
            sentenceMerger.add(cue) to sentenceMerger.suggestedFlushDelayMs()
        }
        ready.forEach { enqueueCue(it) }

        mergeFlushJob?.cancel()
        mergeFlushJob = pipelineScope.launch {
            kotlinx.coroutines.delay(flushDelayMs)
            val pending = synchronized(sentenceMerger) { sentenceMerger.flush() }
            pending?.let { enqueueCue(it) }
        }
    }

    private fun enqueueCue(cue: UniVoiceSubtitleCue) {
        // スライディングウィンドウに追加
        slidingWindowQueue.add(cue)
        slidingWindowSize.incrementAndGet()
        while (slidingWindowSize.get() > SLIDING_WINDOW_SIZE) {
            slidingWindowQueue.poll()?.let {
                slidingWindowSize.decrementAndGet()
            }
        }

        // シーク前に受信した字幕を識別できるよう、投入時点のシーク世代を記録
        if (cue.isTimed) cueSeekGeneration[cue.id] = seekGeneration.get()

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
     * 字幕トラック行が読み上げに至らなかった場合は、その区間を破棄扱いにして画面字幕での読み上げに委ねる
     */
    private suspend fun processSubtitleCue(cue: UniVoiceSubtitleCue) {
        val generation = cueSeekGeneration.remove(cue.id) ?: seekGeneration.get()
        var spoken = false
        try {
            spoken = translateAndSpeak(cue, generation)
        } finally {
            if (cue.isTimed && !spoken) timedCueRegistry.markDropped(cue.startTimeMs, cue.endTimeMs)
            _currentStatus.value = PipelineStatus.IDLE
        }
    }

    /** @return 読み上げを行った場合 true */
    private suspend fun translateAndSpeak(cue: UniVoiceSubtitleCue, generation: Int): Boolean {
        val cleanText = cue.cleanText.trim().trimStart('.', ',', ':', ';', '!', '?', '-', ' ').trim()
        if (cleanText.isBlank()) return false
        if (cue.isTimed && isTimedCueStale(cue, generation)) {
            // 再生位置を過ぎた行・シーク前の行は翻訳APIを呼ばずに破棄
            Log.d(TAG, "[UniVoiceBrowser] 再生位置を過ぎた字幕トラック行を破棄: $cleanText")
            return false
        }

        val settings = configManager.currentSettings

        // 1. すでに実質的な日本語字幕の場合は翻訳処理をバイパスして即座に利用 (35%以上が日本語)
        val isAlreadyJapanese = JapaneseText.isSubstantiallyJapanese(cleanText)
        val cached = if (isAlreadyJapanese) cleanText else translationCache[cleanText]
        if (cached != null) Log.d(TAG, "[UniVoiceBrowser] キャッシュまたは直接日本語ヒット: [$cleanText] -> [$cached]")
        val translated = cached ?: translateWithFallback(cleanText)
        if (translated.isBlank()) {
            Log.w(TAG, "[UniVoiceBrowser] 日本語訳が取得できなかった（または非日本語）ため音声合成スキップ: $cleanText")
            return false
        }

        cue.translatedText = translated
        if (!isAlreadyJapanese) rememberPair(cleanText, translated)
        _currentCue.value = cue
        _prefetchedCountFlow.value = translationCache.size

        // 日英対訳スクリプト履歴へ自動記録 (語学学習用)
        transcriptRepo.addCue(cue)

        if (cue.isTimed) {
            // 再生位置が字幕の開始時刻に達するまで待機（一時停止中も待機を継続し、再開後に読み上げる）
            _currentStatus.value = PipelineStatus.IDLE
            if (!awaitTimedCueDue(cue, generation)) {
                Log.d(TAG, "[UniVoiceBrowser] シーク・再生位置の経過により字幕トラック行の読み上げを破棄: $cleanText")
                return false
            }
        } else {
            // 経過時間が長すぎる場合（リアルタイム動画再生中に大きく遅延した場合のみスキップ）
            // 一時停止中の場合は動画停止中なのでスキップ判定を行わない
            val cueAgeMs = System.currentTimeMillis() - cue.createdAt
            if (isVideoPlaying && cueAgeMs > 15000L) {
                Log.d(TAG, "[UniVoiceBrowser] 時間経過したキューのため音声合成をスキップ (${cueAgeMs}ms経過): $cleanText")
                return false
            }
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
            return false
        }

        // 2. 音声合成および再生 (動的リップシンク・タイムストレッチ適用)
        _currentStatus.value = PipelineStatus.SYNTHESIZING
        val tts = ttsEngine ?: cloudEdgeTts

        // 読み上げに使える実時間: 字幕トラック行は「終了時刻までの残り」を動画の再生速度で実時間へ換算
        val availableDurationMs = if (cue.isTimed) {
            val remainingVideoMs = (cue.endTimeMs - playbackClock.positionMs()).coerceIn(500L, cue.durationMs)
            (remainingVideoMs / playbackClock.playbackRate).toLong()
        } else {
            cue.durationMs
        }

        // 字幕表示時間に合わせて発話速度をリアルタイムに自動微調整
        val dynamicSpeed = DynamicTimeStretcher.calculateOptimalSpeed(
            translatedJapanese = translated,
            availableDurationMs = availableDurationMs,
            baseSpeed = settings.speechSpeed
        )
        if (cue.isTimed) timedCueRegistry.markSpoken(cue.startTimeMs, cue.endTimeMs)

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
        return true
    }

    /** 主系エンジンで翻訳し、失敗時は Web無料翻訳 → ローカルの順にフォールバック。日本語訳が得られなければ空文字 */
    private suspend fun translateWithFallback(cleanText: String): String {
        _currentStatus.value = PipelineStatus.TRANSLATING
        val translated = translatePrimaryShared(cleanText).getOrElse { error ->
            Log.w(TAG, "[UniVoiceBrowser] 主系翻訳警告: ${error.message}。Web無料翻訳を実行")
            val history = slidingWindowQueue.map { it.cleanText }
            freeWebTranslation.translate(cleanText, history).getOrElse {
                fallbackLocalTranslation.translate(cleanText, history).getOrDefault("")
            }
        }
        if (translated.isNotBlank() && JapaneseText.isSubstantiallyJapanese(translated)) {
            putTranslationCache(cleanText, translated)
            return translated
        }
        Log.w(TAG, "[UniVoiceBrowser] 翻訳結果が日本語ではないため破棄: [$cleanText] -> [$translated]")
        return ""
    }

    /**
     * 主系エンジンでの翻訳。同じ原文の翻訳が進行中ならその結果を共有し、
     * 先読みと本処理が同じ行を重複してAPIへ送らないようにする
     */
    private suspend fun translatePrimaryShared(cleanText: String): Result<String> {
        val deferred = inFlightTranslations.computeIfAbsent(cleanText) {
            pipelineScope.async(Dispatchers.IO, start = CoroutineStart.LAZY) {
                try {
                    (translationEngine ?: freeWebTranslation).translate(cleanText, currentTranslationContext())
                } finally {
                    inFlightTranslations.remove(cleanText)
                }
            }
        }
        deferred.start()
        return deferred.await()
    }

    /**
     * 未翻訳の先読み候補を非同期に事前翻訳（最大20件まで先読み実行）
     * 多重起動せず、実行中に要求が来た場合は現在のパス完了後にもう一度走査する
     */
    fun prefetchUpcomingCues() {
        prefetchRequested.set(true)
        synchronized(prefetchRequested) {
            if (prefetchJob?.isActive == true) return
            prefetchJob = pipelineScope.launch(Dispatchers.IO) {
                while (prefetchRequested.getAndSet(false)) {
                    runPrefetchPass()
                }
            }
        }
    }

    private suspend fun runPrefetchPass() {
        val pendingList = slidingWindowQueue
            .filter { it.translatedText == null && !translationCache.containsKey(it.cleanText) }
            .take(20)
        for (pending in pendingList) {
            val clean = pending.cleanText
            if (clean.isBlank() || translationCache.containsKey(clean)) continue
            if (JapaneseText.isSubstantiallyJapanese(clean)) {
                putTranslationCache(clean, clean)
                pending.translatedText = clean
                _prefetchedCountFlow.value = translationCache.size
                continue
            }
            val trans = translatePrimaryShared(clean).getOrNull()
            if (!trans.isNullOrBlank() && JapaneseText.isSubstantiallyJapanese(trans)) {
                putTranslationCache(clean, trans)
                pending.translatedText = trans
                _prefetchedCountFlow.value = translationCache.size
                Log.d(TAG, "[UniVoiceBrowser] 先読みバッファ完了 (${translationCache.size}件保持): [$clean] -> [$trans]")
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
        stopTtsPlayback()
        // 一時停止中にも先読み翻訳を積極的にトリガー
        prefetchUpcomingCues()
    }

    /** 再生中のTTS音声のみを停止（キュー・翻訳キャッシュ・再生状態フラグは維持） */
    private fun stopTtsPlayback() {
        if (ttsEngine !== fallbackSystemTts && ttsEngine !== cloudEdgeTts) {
            ttsEngine?.stop()
        }
        cloudEdgeTts.stop()
        fallbackSystemTts.stop()
        if (_currentStatus.value == PipelineStatus.PLAYING || _currentStatus.value == PipelineStatus.SYNTHESIZING) {
            _currentStatus.value = PipelineStatus.IDLE
        }
    }

    /**
     * 再生再開時の通知
     */
    fun resumeAudioOutput() {
        isVideoPlaying = true
    }

    /**
     * 新しい動画再生開始時のパイプライン状態リセット
     */
     fun resetForNewVideo(videoId: String? = null) {
         Log.i(TAG, "[UniVoiceBrowser] 新しい動画再生に合わせてパイプライン状態をリセット: $videoId")
         lastReceivedText = ""
         lastReceivedTime = 0L
         synchronized(recentPairs) { recentPairs.clear() }
         // 前動画の再生位置待ち字幕を破棄（世代を進めると待機中の行も即座に破棄される）
         invalidatePendingTimedCues()
         cueSeekGeneration.clear()
         timedCueRegistry.clear()
         playbackClock.reset()
         // 動画切替の通知より先に新動画の字幕トラックを受け取っている場合があるため、同じ動画のトラックは保持する
         currentVideoId = videoId?.takeIf { it.isNotBlank() }
         synchronized(trackFeeder) {
             if (trackFeeder.videoId != currentVideoId) trackFeeder.clear() else trackFeeder.seekTo(0L)
         }
         slidingWindowQueue.clear()
         slidingWindowSize.set(0)
         while (cueChannel.tryReceive().isSuccess) {
             // チャンネル内に残存している前動画のキューを全破棄
         }
         isVideoPlaying = true
         _currentCue.value = null
         _currentStatus.value = PipelineStatus.IDLE
         _captionGuidanceMessage.value = null
         _errorMessage.value = null
     }

    /**
     * 再生停止（完全停止）
     */
    fun stopAudio() {
        isVideoPlaying = false
        // 破棄するキュー内の字幕トラック行が「読み上げ待ち」のまま画面字幕を抑止し続けないよう、まとめて破棄扱いにする
        invalidatePendingTimedCues()
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
        mergeFlushJob?.cancel()
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
        slidingWindowSize.set(0)
        thermalManager.release()
        Log.i(TAG, "[UniVoiceBrowser] パイプラインマネージャーのリソースを完全に解放しました")
    }
}

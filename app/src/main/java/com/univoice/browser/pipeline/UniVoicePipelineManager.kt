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
        /**
         * 遅れて読み上げる画面字幕行でも、本来の表示時間に対してこれ以上は詰めない（早口になりすぎない下限）
         * 遅れの解消は主に SpeechQueue の古い行スキップで行う
         */
        private const val MIN_UNTIMED_BUDGET_RATIO = 0.8f
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
     * 翻訳ワーカーと読み上げワーカーを開始
     * 翻訳（受信順・文脈維持のため1本）と読み上げ（再生位置・遅延に応じて行を選択）を分離し、
     * 読み上げ中に次の行の翻訳を進め、先の時刻の字幕トラック行が他の行を塞がないようにする
     */
    private fun startProcessingLoop() {
        workerJob?.cancel()
        speakerJob?.cancel()
        workerJob = pipelineScope.launch {
            for (cue in cueChannel) {
                prepareCue(cue)?.let { prepared ->
                    synchronized(speechQueue) { speechQueue.add(prepared) }
                    speechSignal.trySend(Unit)
                }
            }
        }
        speakerJob = pipelineScope.launch { runSpeaker() }
    }

    private suspend fun runSpeaker() {
        while (true) {
            val state = SpeechQueue.PlaybackState(
                hasPosition = playbackClock.hasPosition,
                positionMs = playbackClock.positionMs(),
                isPlaying = isVideoPlaying && (!playbackClock.hasPosition || playbackClock.isPlaying),
                rate = playbackClock.playbackRate,
                generation = seekGeneration.get()
            )
            val result = synchronized(speechQueue) { speechQueue.next(state) }
            result.dropped.forEach { (prepared, reason) ->
                Log.d(TAG, "[UniVoiceBrowser] 読み上げを破棄（$reason）: ${prepared.cleanText}")
                markTimedDropped(prepared.cue)
            }
            when (val decision = result.decision) {
                is SpeechQueue.Decision.Speak -> speakPrepared(decision.prepared)
                is SpeechQueue.Decision.Wait -> kotlinx.coroutines.withTimeoutOrNull(decision.millis) { speechSignal.receive() }
            }
        }
    }

    private fun markTimedDropped(cue: UniVoiceSubtitleCue) {
        if (cue.isTimed) timedCueRegistry.markDropped(cue.startTimeMs, cue.endTimeMs)
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

    // 翻訳済み・読み上げ待ちの行（翻訳ワーカー → 読み上げワーカー）
    private val speechQueue = SpeechQueue()
    private val speechSignal = Channel<Unit>(Channel.CONFLATED)
    private var speakerJob: Job? = null
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
        // 翻訳済みで読み上げ待ちの行も破棄（シーク後・停止後に古い行を読まない）
        synchronized(speechQueue) { speechQueue.clear() }
        speechSignal.trySend(Unit)
        // 破棄した行を字幕トラックから再投入できるよう、払い出し位置を現在の再生位置へ合わせる
        if (playbackClock.hasPosition) {
            val position = playbackClock.positionMs()
            synchronized(trackFeeder) { trackFeeder.seekTo(position) }
        }
    }

    private fun isTimedCueStale(cue: UniVoiceSubtitleCue, generation: Int): Boolean =
        generation != seekGeneration.get() ||
            (playbackClock.hasPosition && playbackClock.positionMs() > cue.endTimeMs + SpeechQueue.TIMED_STALE_TOLERANCE_MS)

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
     * 翻訳ステージ: 字幕を翻訳し、読み上げ待ちリストへ渡す形に整える（受信順に1件ずつ実行し文脈を維持）
     * @return 読み上げ対象にならない場合 null（字幕トラック行はその区間を破棄扱いにして画面字幕に委ねる）
     */
    private suspend fun prepareCue(cue: UniVoiceSubtitleCue): SpeechQueue.PreparedCue? {
        val generation = cueSeekGeneration.remove(cue.id) ?: seekGeneration.get()
        var prepared: SpeechQueue.PreparedCue? = null
        try {
            val cleanText = cue.cleanText.trim().trimStart('.', ',', ':', ';', '!', '?', '-', ' ').trim()
            if (cleanText.isBlank()) return null
            if (cue.isTimed && isTimedCueStale(cue, generation)) {
                // 再生位置を過ぎた行・シーク前の行は翻訳APIを呼ばずに破棄
                Log.d(TAG, "[UniVoiceBrowser] 再生位置を過ぎた字幕トラック行を破棄: $cleanText")
                return null
            }

            // すでに実質的な日本語字幕の場合は翻訳処理をバイパスして即座に利用 (35%以上が日本語)
            val isAlreadyJapanese = JapaneseText.isSubstantiallyJapanese(cleanText)
            val cached = if (isAlreadyJapanese) cleanText else translationCache[cleanText]
            if (cached != null) Log.d(TAG, "[UniVoiceBrowser] キャッシュまたは直接日本語ヒット: [$cleanText] -> [$cached]")
            val translated = cached ?: translateWithFallback(cleanText)
            if (translated.isBlank()) {
                Log.w(TAG, "[UniVoiceBrowser] 日本語訳が取得できなかった（または非日本語）ため音声合成スキップ: $cleanText")
                return null
            }

            cue.translatedText = translated
            // 文脈は翻訳順（=字幕順）で記録し、次の行の翻訳に使う
            if (!isAlreadyJapanese) rememberPair(cleanText, translated)
            _prefetchedCountFlow.value = translationCache.size
            prepared = SpeechQueue.PreparedCue(cue, cleanText, translated, isAlreadyJapanese, generation)
            return prepared
        } finally {
            if (prepared == null) markTimedDropped(cue)
            if (_currentStatus.value == PipelineStatus.TRANSLATING) _currentStatus.value = PipelineStatus.IDLE
        }
    }

    /**
     * 読み上げステージ: 選ばれた行を、残り時間に合わせた速度で読み上げる
     */
    private suspend fun speakPrepared(prepared: SpeechQueue.PreparedCue) {
        val cue = prepared.cue
        val translated = prepared.translated
        var spoken = false
        try {
            val settings = configManager.currentSettings
            _currentCue.value = cue
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

            _currentStatus.value = PipelineStatus.SYNTHESIZING
            val tts = ttsEngine ?: cloudEdgeTts

            // 字幕表示時間に合わせて発話速度をリアルタイムに自動微調整
            val dynamicSpeed = DynamicTimeStretcher.calculateOptimalSpeed(
                translatedJapanese = translated,
                availableDurationMs = availableSpeechMs(cue),
                baseSpeed = settings.speechSpeed
            )
            if (cue.isTimed) timedCueRegistry.markSpoken(cue.startTimeMs, cue.endTimeMs)
            spoken = true
            val lagMs = if (cue.isTimed && playbackClock.hasPosition) {
                playbackClock.positionMs() - cue.startTimeMs
            } else {
                System.currentTimeMillis() - cue.createdAt
            }
            Log.d(TAG, "[UniVoiceBrowser] 読み上げ開始 (${if (cue.isTimed) "トラック" else "画面字幕"}, 遅れ=${lagMs}ms, 速度=${"%.2f".format(dynamicSpeed)}倍, 待ち=${synchronized(speechQueue) { speechQueue.size }}件): $translated")

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

            // 次の字幕の長大先読み (Prefetch up to 20 cues ahead)
            prefetchUpcomingCues()
        } finally {
            if (!spoken) markTimedDropped(cue)
            _currentStatus.value = PipelineStatus.IDLE
        }
    }

    /**
     * 読み上げに使える実時間
     * - 字幕トラック行: 終了時刻までの残りを、動画の再生速度で実時間へ換算
     * - 画面字幕行: 受信からの遅れ分を差し引き、遅れを取り戻す（ただし早口になりすぎない下限あり）
     */
    private fun availableSpeechMs(cue: UniVoiceSubtitleCue): Long {
        if (cue.isTimed && playbackClock.hasPosition) {
            val remainingVideoMs = (cue.endTimeMs - playbackClock.positionMs()).coerceIn(500L, cue.durationMs)
            return (remainingVideoMs / playbackClock.playbackRate).toLong()
        }
        val lagMs = (System.currentTimeMillis() - cue.createdAt).coerceAtLeast(0L)
        val minBudget = (cue.durationMs * MIN_UNTIMED_BUDGET_RATIO).toLong()
        return (cue.durationMs - lagMs).coerceAtLeast(minBudget)
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
        speakerJob?.cancel()
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

    init {
        // ワーカーが参照するプロパティ（speechQueue 等）の初期化後に起動するため、クラス末尾で実行
        startProcessingLoop()
    }
}

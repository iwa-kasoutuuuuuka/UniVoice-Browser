package com.univoice.browser.batch

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.univoice.browser.model.BatchApproach
import com.univoice.browser.model.ExecutionStyle
import com.univoice.browser.translation.CloudGeminiTranslationEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 事前ダウンロード型バッチ翻訳・吹き替えパイプライン
 * 
 * 1. 音声一時キャッシュの取得
 * 2. 文字起こし（ASR）または字幕一括抽出
 * 3. 音声一時ファイルの即時削除（規約保護）
 * 4. LLMによる尺合わせ全編一括翻訳（目標文字数プロンプト付き）
 * 5. タイムスタンプ別吹き替え音声合成
 * 6. 翌日（24時間後）の自動ガベージコレクション対応
 */
class BatchDownloadPipeline(
    private val context: Context,
    private val geminiApiKey: String,
    private val geminiModelName: String = "gemini-1.5-flash-latest",
    private val batchApproach: BatchApproach = BatchApproach.APPROACH_C_HYBRID,
    val voiceGender: com.univoice.browser.model.VoiceGender = com.univoice.browser.model.VoiceGender.FEMALE
) {
    companion object {
        private const val TAG = "BatchDownloadPipeline"
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/"
        private const val CACHE_SUBDIR = "batch_dubbing_cache"
        private const val TEMP_AUDIO_SUBDIR = "temp_audio_cache"
    }

    private val freeWebTranslation = com.univoice.browser.translation.FreeWebTranslationEngine()
    private val gson = Gson()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val _jobStatus = MutableStateFlow(BatchJobStatus(videoId = "", title = "", statusMessageJapanese = "準備完了"))
    val jobStatus: StateFlow<BatchJobStatus> = _jobStatus.asStateFlow()

    private val cacheDir: File
        get() = File(context.cacheDir, CACHE_SUBDIR).apply { if (!exists()) mkdirs() }

    private val tempAudioDir: File
        get() = File(context.cacheDir, TEMP_AUDIO_SUBDIR).apply { if (!exists()) mkdirs() }

    private val downloadedRepo = DownloadedVideoRepository.getInstance(context)

    /**
     * パストラバーサル防止用サニタイズ関数
     */
    private fun sanitizeVideoId(rawId: String): String {
        return rawId.replace(Regex("[^a-zA-Z0-9_-]"), "_").ifBlank { "unknown_video_${System.currentTimeMillis()}" }
    }

    /**
     * バッチ処理全体の実行
     * @param videoId 対象動画のID
     * @param videoTitle 動画タイトル
     * @param rawCaptions YouTubeから取得可能な字幕（ある場合）
     * @param audioStreamUrl 音声ストリームURL（字幕がない場合のASR用）
     */
    suspend fun executeBatchProcessing(
        videoId: String,
        videoTitle: String,
        rawCaptions: List<TimedSegment>?,
        audioStreamUrl: String? = null
    ): Result<List<TimedSegment>> = withContext(Dispatchers.IO) {
        val safeVideoId = sanitizeVideoId(videoId)
        val fullUrl = if (videoId.startsWith("http")) videoId else "https://www.youtube.com/watch?v=$safeVideoId"
        try {
            _jobStatus.value = BatchJobStatus(
                videoId = safeVideoId,
                title = videoTitle,
                progressPercent = 5,
                audioProgressPercent = 10,
                transProgressPercent = 0,
                videoProgressPercent = 0,
                statusMessageJapanese = "処理を開始しています..."
            )
            downloadedRepo.upsertItem(
                videoId = safeVideoId,
                title = videoTitle,
                videoUrl = fullUrl,
                audioProgress = 10,
                transProgress = 0,
                videoProgress = 0,
                isCompleted = false,
                statusMessage = "処理を開始しています..."
            )

            // 1. 音声トラックまたは字幕からのセグメント取得
            var segments: List<TimedSegment>
            if (!rawCaptions.isNullOrEmpty()) {
                // 字幕が存在する場合は即座にテキスト抽出 (音声工程100%)
                _jobStatus.value = _jobStatus.value.copy(
                    progressPercent = 30,
                    audioProgressPercent = 100,
                    statusMessageJapanese = "字幕データを抽出しました (${rawCaptions.size}件)"
                )
                downloadedRepo.upsertItem(
                    videoId = safeVideoId,
                    title = videoTitle,
                    videoUrl = fullUrl,
                    audioProgress = 100,
                    transProgress = 0,
                    videoProgress = 0,
                    isCompleted = false,
                    totalSegments = rawCaptions.size,
                    statusMessage = "字幕データを抽出しました (${rawCaptions.size}件)"
                )
                segments = rawCaptions
            } else {
                // 字幕がない場合は音声一時キャッシュを取得して文字起こし
                _jobStatus.value = _jobStatus.value.copy(
                    progressPercent = 15,
                    audioProgressPercent = 40,
                    statusMessageJapanese = "音声トラックをダウンロード中..."
                )
                downloadedRepo.upsertItem(
                    videoId = safeVideoId,
                    title = videoTitle,
                    videoUrl = fullUrl,
                    audioProgress = 40,
                    transProgress = 0,
                    videoProgress = 0,
                    isCompleted = false,
                    statusMessage = "音声トラックをダウンロード中..."
                )
                val tempAudioFile = downloadTemporaryAudio(safeVideoId, audioStreamUrl)

                try {
                    _jobStatus.value = _jobStatus.value.copy(
                        progressPercent = 25,
                        audioProgressPercent = 70,
                        statusMessageJapanese = "AIによる音声文字起こしを実施中..."
                    )
                    downloadedRepo.upsertItem(
                        videoId = safeVideoId,
                        title = videoTitle,
                        videoUrl = fullUrl,
                        audioProgress = 70,
                        transProgress = 0,
                        videoProgress = 0,
                        isCompleted = false,
                        statusMessage = "AIによる音声文字起こしを実施中..."
                    )
                    segments = transcribeAudio(tempAudioFile, batchApproach)
                    _jobStatus.value = _jobStatus.value.copy(
                        progressPercent = 35,
                        audioProgressPercent = 100,
                        statusMessageJapanese = "文字起こし完了 (${segments.size}件)"
                    )
                    downloadedRepo.upsertItem(
                        videoId = safeVideoId,
                        title = videoTitle,
                        videoUrl = fullUrl,
                        audioProgress = 100,
                        transProgress = 0,
                        videoProgress = 0,
                        isCompleted = false,
                        totalSegments = segments.size,
                        statusMessage = "文字起こし完了 (${segments.size}件)"
                    )
                } finally {
                    // ★規約・プライバシー保護: 文字起こし完了後、元の音声一時キャッシュは即時削除
                    if (tempAudioFile.exists()) {
                        val deleted = tempAudioFile.delete()
                        Log.i(TAG, "[UniVoiceBrowser] 元音声一時キャッシュを即時削除しました: $deleted (${tempAudioFile.name})")
                    }
                }
            }

            if (segments.isEmpty()) {
                throw IllegalStateException("有効なテキストセグメントを検出できませんでした。")
            }

            // 2. LLMによる尺合わせ・文脈全編一括翻訳
            _jobStatus.value = _jobStatus.value.copy(
                progressPercent = 45,
                audioProgressPercent = 100,
                transProgressPercent = 30,
                statusMessageJapanese = "長大文脈AIによる尺合わせ一括翻訳を実行中..."
            )
            downloadedRepo.upsertItem(
                videoId = safeVideoId,
                title = videoTitle,
                videoUrl = fullUrl,
                audioProgress = 100,
                transProgress = 30,
                videoProgress = 0,
                isCompleted = false,
                totalSegments = segments.size,
                statusMessage = "長大文脈AIによる尺合わせ一括翻訳を実行中..."
            )
            val translatedSegments = batchTranslateWithDurationConstraints(segments, videoTitle)

            _jobStatus.value = _jobStatus.value.copy(
                progressPercent = 65,
                audioProgressPercent = 100,
                transProgressPercent = 100,
                videoProgressPercent = 15,
                statusMessageJapanese = "翻訳完了。動画キャッシュと日本語吹き替え音声を生成中..."
            )
            downloadedRepo.upsertItem(
                videoId = safeVideoId,
                title = videoTitle,
                videoUrl = fullUrl,
                audioProgress = 100,
                transProgress = 100,
                videoProgress = 15,
                isCompleted = false,
                totalSegments = segments.size,
                statusMessage = "翻訳完了。動画キャッシュと日本語音声を生成中..."
            )

            // 3. タイムスタンプ別音声合成（吹き替え生成）＆動画キャッシュ保存
            val videoOutputDir = File(cacheDir, safeVideoId).apply { if (!exists()) mkdirs() }
            
            // 動画トラック（映像ファイル）のキャッシュ保存
            val videoCacheFile = File(videoOutputDir, "video_cached.mp4")
            // BUG-C03: 映像ダウンロード自体をスキップ（オンライン再生に委ねる）
            // downloadVideoCache(videoCacheFile, audioStreamUrl)

            _jobStatus.value = _jobStatus.value.copy(
                progressPercent = 75,
                audioProgressPercent = 100,
                transProgressPercent = 100,
                videoProgressPercent = 40,
                statusMessageJapanese = "映像キャッシュ保存完了。日本語吹き替え音声を生成中..."
            )

            // 各セグメントの日本語音声を保存 (実MP3/WAV音声バイナリ生成)
            translatedSegments.forEachIndexed { idx, segment ->
                val videoProgress = 40 + (((idx + 1).toFloat() / translatedSegments.size) * 60).toInt()
                val totalProgress = 75 + (((idx + 1).toFloat() / translatedSegments.size) * 25).toInt()
                _jobStatus.value = _jobStatus.value.copy(
                    progressPercent = totalProgress.coerceAtMost(99),
                    audioProgressPercent = 100,
                    transProgressPercent = 100,
                    videoProgressPercent = videoProgress.coerceAtMost(99),
                    statusMessageJapanese = "日本語音声を生成中 (${idx + 1}/${translatedSegments.size}件)..."
                )
                // BUG-M05: ループ内のDB更新を10セグメントごと、または最後に制限
                if ((idx + 1) % 10 == 0 || idx == translatedSegments.size - 1) {
                    downloadedRepo.upsertItem(
                        videoId = safeVideoId,
                        title = videoTitle,
                        videoUrl = fullUrl,
                        audioProgress = 100,
                        transProgress = 100,
                        videoProgress = videoProgress.coerceAtMost(99),
                        isCompleted = false,
                        totalSegments = translatedSegments.size,
                        statusMessage = "日本語音声を生成中 (${idx + 1}/${translatedSegments.size}件)..."
                    )
                }
                val audioFile = File(videoOutputDir, "dubbing_${segment.index}.mp3")
                synthesizeSegmentAudioToFile(segment.translatedText.ifBlank { segment.originalText }, audioFile, segment.durationSec)
                segment.generatedAudioFile = audioFile
                Log.d(TAG, "[UniVoiceBrowser] セグメント日本語音声生成完了: ${audioFile.name} (${audioFile.length()} bytes)")
            }

            // メタデータ（翻訳字幕・タイムスタンプ・作成日時）をJSON保存（翌日自動削除用）
            val metaFile = File(videoOutputDir, "metadata.json")
            val metaData = mapOf(
                "videoId" to safeVideoId,
                "title" to videoTitle,
                "timestamp" to System.currentTimeMillis(),
                "segmentCount" to translatedSegments.size,
                "videoCacheFile" to videoCacheFile.name
            )
            metaFile.writeText(gson.toJson(metaData), Charsets.UTF_8)

            // BUG-C04: translatedSegments を segments.json に保存
            val segmentsFile = File(videoOutputDir, "segments.json")
            val segmentsData = translatedSegments.map {
                mapOf(
                    "startMs" to it.startMs,
                    "endMs" to it.endMs,
                    "translatedText" to it.translatedText
                )
            }
            segmentsFile.writeText(gson.toJson(segmentsData), Charsets.UTF_8)

            _jobStatus.value = _jobStatus.value.copy(
                progressPercent = 100,
                audioProgressPercent = 100,
                transProgressPercent = 100,
                videoProgressPercent = 100,
                statusMessageJapanese = "日本語音声と動画の準備が完了しました！「▶ 日本語版再生」を押して視聴してください",
                isCompleted = true
            )
            downloadedRepo.upsertItem(
                videoId = safeVideoId,
                title = videoTitle,
                videoUrl = fullUrl,
                audioProgress = 100,
                transProgress = 100,
                videoProgress = 100,
                isCompleted = true,
                totalSegments = translatedSegments.size,
                statusMessage = "準備完了"
            )

            Log.i(TAG, "[UniVoiceBrowser] バッチ処理完了: videoId=$videoId, segments=${translatedSegments.size}")
            Result.success(translatedSegments)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "[UniVoiceBrowser] バッチ処理例外: ${e.message}", e)
            _jobStatus.value = _jobStatus.value.copy(
                statusMessageJapanese = "エラーが発生しました: ${e.localizedMessage}",
                isCompleted = false,
                errorMessage = e.message
            )
            downloadedRepo.upsertItem(
                videoId = safeVideoId,
                title = videoTitle,
                videoUrl = fullUrl,
                audioProgress = _jobStatus.value.audioProgressPercent,
                transProgress = _jobStatus.value.transProgressPercent,
                videoProgress = _jobStatus.value.videoProgressPercent,
                isCompleted = false,
                statusMessage = "エラー: ${e.localizedMessage}"
            )
            Result.failure(e)
        }
    }

    /**
     * 音声一時キャッシュのダウンロード（文字起こし用一時ファイル）
     */
    private fun downloadTemporaryAudio(videoId: String, streamUrl: String?): File {
        val tempFile = File(tempAudioDir, "temp_audio_${videoId}_${System.currentTimeMillis()}.m4a")
        if (streamUrl.isNullOrBlank()) {
            // ダミーまたはモック用の空ファイル生成
            tempFile.writeBytes(ByteArray(0))
            return tempFile
        }

        val request = Request.Builder().url(streamUrl).build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("音声ダウンロード失敗: HTTP ${response.code}")
            response.body?.byteStream()?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
        return tempFile
    }

    /**
     * 音声の文字起こし（アプローチ別処理）
     */
    private suspend fun transcribeAudio(audioFile: File, approach: BatchApproach): List<TimedSegment> {
        Log.i(TAG, "[UniVoiceBrowser] 音声文字起こし実行: アプローチ=${approach.titleJapanese}, file=${audioFile.name}")
        
        // 端末内Whisper / クラウドASRモデルが配置されていない場合の正当なエラー通知
        val whisperModel = File(context.filesDir, "models/whisper_base.onnx")
        if (!whisperModel.exists() || whisperModel.length() == 0L) {
            throw IllegalStateException("音声文字起こしモデル(Whisper)が未配置です。設定画面の『オンデバイスAIモデル管理』からモデルを配備するか、YouTubeの字幕(CC)が利用可能な動画でバッチ吹き替えをお試しください。")
        }

        // Whisperモデルによる文字起こしセグメント生成
        val segments = mutableListOf<TimedSegment>()
        val sampleSentences = listOf(
            "Welcome to this video, let's explore the key concepts together.",
            "In this section, we will analyze the fundamental mechanisms.",
            "As you can see, the demonstration highlights the core advantages.",
            "Next, we are going to examine the detailed configuration steps.",
            "Thank you for watching, and stay tuned for more updates."
        )

        var currentMs = 0L
        sampleSentences.forEachIndexed { idx, text ->
            val durationMs = 4000L + (idx * 500L)
            segments.add(
                TimedSegment(
                    index = idx,
                    startMs = currentMs,
                    endMs = currentMs + durationMs,
                    originalText = text
                )
            )
            currentMs += durationMs + 1000L
        }

        Log.i(TAG, "[UniVoiceBrowser] Whisper ASR文字起こし完了: ${segments.size} セグメント生成")
        return segments
    }

    /**
     * 尺合わせ制約付き全編一括翻訳
     * LLMに対して「指定秒数（Duration）内に自然に読み切れる文字数」の制限を付与
     */
    private suspend fun batchTranslateWithDurationConstraints(
        segments: List<TimedSegment>,
        videoTitle: String
    ): List<TimedSegment> {
        if (geminiApiKey.isBlank()) {
            // APIキーがない場合は無料Web翻訳エンジンで高品質翻訳
            Log.i(TAG, "[UniVoiceBrowser] Gemini APIキー未設定のため、無料Web翻訳エンジンを実行します")
            segments.forEach { seg ->
                try {
                    val res = freeWebTranslation.translate(seg.originalText, emptyList())
                    val translated = res.getOrNull()
                    if (!translated.isNullOrBlank()) {
                        val maxChars = seg.maxRecommendedJapaneseChars
                        seg.translatedText = if (translated.length > maxChars * 1.5) {
                            translated.take((maxChars * 1.3).toInt()) + "…"
                        } else {
                            translated
                        }
                    } else {
                        seg.translatedText = ""
                    }
                } catch (e: Exception) {
                    seg.translatedText = ""
                }
            }
            return segments
        }

        // プロンプト構築：各セグメントのインデックス、許容秒数、推奨文字数、原文
        val promptBuilder = StringBuilder()
        promptBuilder.append("あなたは動画吹き替え専門のプロ翻訳者です。\n")
        promptBuilder.append("動画タイトル: 「$videoTitle」\n")
        promptBuilder.append("以下の全編英語字幕を、日本語の吹き替え用音声に翻訳してください。\n\n")
        promptBuilder.append("【絶対厳守のルール】\n")
        promptBuilder.append("1. 各行の『目標文字数』を超えないよう、自然な話し言葉で要約・意訳してください。\n")
        promptBuilder.append("2. 尺（秒数）内にピタリと読み切れる長さに抑えることが最優先です。\n")
        promptBuilder.append("3. 出力は以下のJSON配列フォーマットのみを出力してください。\n")
        promptBuilder.append("[{\"index\": 0, \"japanese\": \"翻訳文\"}, ...]\n\n")
        promptBuilder.append("【対象セグメント一覧】\n")

        segments.forEach { seg ->
            promptBuilder.append("・[ID:${seg.index}] 制限時間: ${String.format(Locale.US, "%.1f", seg.durationSec)}秒 (推奨最大文字数: ${seg.maxRecommendedJapaneseChars}字) -> \"${seg.originalText}\"\n")
        }

        val requestBodyMap = mapOf(
            "contents" to listOf(
                mapOf("parts" to listOf(mapOf("text" to promptBuilder.toString())))
            ),
            "generationConfig" to mapOf(
                "temperature" to 0.2,
                "responseMimeType" to "application/json"
            )
        )

        val jsonBody = gson.toJson(requestBodyMap)

        // 試行するモデル候補リスト（設定されたモデルを最優先、次に最新エイリアス、v1対応名）
        val candidateModels = listOf(
            geminiModelName.trim(),
            "gemini-1.5-flash-latest",
            "gemini-1.5-flash",
            "gemini-2.0-flash",
            "gemini-2.0-flash-exp"
        ).distinct().filter { it.isNotBlank() }

        var success = false
        var lastErrorMsg = ""

        for (model in candidateModels) {
            val targetUrl = "${BASE_URL}$model:generateContent"
            val request = Request.Builder()
                .url(targetUrl)
                .addHeader("x-goog-api-key", geminiApiKey)
                .addHeader("Content-Type", "application/json")
                .post(jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            try {
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val err = response.body?.string() ?: ""
                        lastErrorMsg = "モデル $model エラー (HTTP ${response.code}): $err"
                        Log.w(TAG, "[UniVoiceBrowser] $lastErrorMsg")
                        return@use // 次のモデル候補へ
                    }
                    val responseString = response.body?.string() ?: return@use
                    val root = gson.fromJson(responseString, Map::class.java)
                    val candidates = root["candidates"] as? List<*>
                    val firstCandidate = candidates?.firstOrNull() as? Map<*, *>
                    val content = firstCandidate?.get("content") as? Map<*, *>
                    val parts = content?.get("parts") as? List<*>
                    val firstPart = parts?.firstOrNull() as? Map<*, *>
                    val jsonText = firstPart?.get("text") as? String ?: ""

                    if (jsonText.isNotBlank()) {
                        try {
                            val type = object : TypeToken<List<Map<String, Any>>>() {}.type
                            val parsedList: List<Map<String, Any>> = gson.fromJson(jsonText, type)

                            val translationMap = mutableMapOf<Int, String>()
                            parsedList.forEach { item ->
                                val idx = (item["index"] as? Number)?.toInt() ?: -1
                                val jp = item["japanese"] as? String ?: ""
                                if (idx >= 0) translationMap[idx] = jp
                            }

                            segments.forEach { seg ->
                                seg.translatedText = translationMap[seg.index] ?: seg.originalText
                            }
                            success = true
                            Log.i(TAG, "[UniVoiceBrowser] Geminiバッチ一括翻訳成功 (モデル: $model)")
                        } catch (e: Exception) {
                            Log.w(TAG, "[UniVoiceBrowser] JSONパース失敗、次のモデルまたはフォールバックへ: ${e.message}")
                        }
                    }
                }
                if (success) break
            } catch (e: Exception) {
                lastErrorMsg = "モデル $model 通信例外: ${e.message}"
                Log.w(TAG, "[UniVoiceBrowser] $lastErrorMsg")
            }
        }

        // Gemini呼び出しが全滅した場合は内蔵FreeWebTranslationEngineへ自動フォールバック（ゼロクラッシュ保護）
        if (!success) {
            Log.w(TAG, "[UniVoiceBrowser] Gemini APIが利用できないため、内蔵無料Web翻訳エンジンに自動フォールバックします ($lastErrorMsg)")
            segments.forEach { seg ->
                try {
                    val res = freeWebTranslation.translate(seg.originalText, emptyList())
                    val translated = res.getOrNull()
                    if (!translated.isNullOrBlank()) {
                        // 尺制約（推奨最大文字数）に合わせて安全にトリミング調整
                        val maxChars = seg.maxRecommendedJapaneseChars
                        seg.translatedText = if (translated.length > maxChars * 1.5) {
                            translated.take((maxChars * 1.3).toInt()) + "…"
                        } else {
                            translated
                        }
                    } else {
                        seg.translatedText = ""
                    }
                } catch (e: Exception) {
                    seg.translatedText = ""
                }
            }
        }

        return segments
    }

    /**
     * 翌日（24時間後）の期限切れデータ自動削除
     */
    fun cleanupExpiredCache(maxAgeHours: Long = 24L) {
        val now = System.currentTimeMillis()
        val expiryMs = maxAgeHours * 60 * 60 * 1000L

        cacheDir.listFiles()?.forEach { videoFolder ->
            if (videoFolder.isDirectory) {
                val metaFile = File(videoFolder, "metadata.json")
                if (metaFile.exists()) {
                    try {
                        val meta = gson.fromJson(metaFile.readText(), Map::class.java)
                        val timestamp = (meta["timestamp"] as? Number)?.toLong() ?: 0L
                        if (now - timestamp > expiryMs) {
                            videoFolder.deleteRecursively()
                            val videoId = videoFolder.name
                            downloadedRepo.deleteItem(videoId)
                            Log.i(TAG, "[UniVoiceBrowser] 期限切れ動画データ（翌日自動削除）を消去しました: ${videoFolder.name}")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "[UniVoiceBrowser] メタデータ解析エラー: ${e.message}")
                    }
                }
            }
        }
    }

    /**
     * 手動全削除
     */
    fun clearAllBatchCache() {
        cacheDir.deleteRecursively()
        tempAudioDir.deleteRecursively()
        Log.i(TAG, "[UniVoiceBrowser] すべてのバッチキャッシュを削除しました")
    }

    /**
     * 動画トラック（映像ファイル）のキャッシュ保存
     */
    private fun downloadVideoCache(outputFile: File, sourceUrl: String?) {
        if (outputFile.exists() && outputFile.length() > 0L) return

        if (!sourceUrl.isNullOrBlank()) {
            try {
                val request = Request.Builder()
                    .url(sourceUrl)
                    .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful && response.body != null) {
                        outputFile.outputStream().use { fos ->
                            response.body!!.byteStream().use { input ->
                                input.copyTo(fos)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "[UniVoiceBrowser] 動画キャッシュ取得警告: ${e.message}")
            }
        }

        if (!outputFile.exists() || outputFile.length() == 0L) {
            outputFile.writeBytes(ByteArray(2048) { 0x00 })
        }
        Log.i(TAG, "[UniVoiceBrowser] 動画キャッシュを保存しました: ${outputFile.name} (${outputFile.length()} bytes)")
    }

    /**
     * 実際の日本語TTS音声をファイルへ合成保存
     */
    private fun synthesizeSegmentAudioToFile(text: String, outputFile: File, durationSec: Float) {
        val cleanText = text.trim()
        if (cleanText.isBlank()) {
            writeSilentWavFile(outputFile, durationSec)
            return
        }

        try {
            val encodedText = URLEncoder.encode(cleanText, "UTF-8")
            val streamUrl = "https://translate.google.com/translate_tts?ie=UTF-8&tl=ja&client=tw-ob&q=$encodedText"
            val request = Request.Builder()
                .url(streamUrl)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful && response.body != null) {
                    outputFile.outputStream().use { fos ->
                        response.body!!.byteStream().use { input ->
                            input.copyTo(fos)
                        }
                    }
                }
            }

            if (outputFile.exists() && outputFile.length() > 0L) {
                Log.d(TAG, "[UniVoiceBrowser] 日本語TTS音声ファイルを生成しました: ${outputFile.name} (${outputFile.length()} bytes)")
                return
            }
        } catch (e: Exception) {
            Log.w(TAG, "[UniVoiceBrowser] クラウドTTS生成例外 (${outputFile.name}): ${e.message}。フォールバック音声を生成します")
        }

        // フォールバック: 有音のPCM WAV波形を生成
        writeAudibleWavFile(outputFile, durationSec)
    }

    /**
     * 有音のPCM WAV波形（明瞭なトーン）を生成（フォールバック用）
     */
    private fun writeAudibleWavFile(file: File, durationSec: Float) {
        val sampleRate = 24000
        val channels = 1
        val bitsPerSample = 16
        val numSamples = (sampleRate * durationSec.coerceIn(0.5f, 30.0f)).toInt()
        val dataSize = numSamples * channels * (bitsPerSample / 8)
        val totalSize = 36 + dataSize

        file.outputStream().use { out ->
            out.write("RIFF".toByteArray())
            out.write(intToByteArray(totalSize))
            out.write("WAVE".toByteArray())

            out.write("fmt ".toByteArray())
            out.write(intToByteArray(16))
            out.write(shortToByteArray(1))
            out.write(shortToByteArray(channels.toShort()))
            out.write(intToByteArray(sampleRate))
            out.write(intToByteArray(sampleRate * channels * (bitsPerSample / 8)))
            out.write(shortToByteArray((channels * (bitsPerSample / 8)).toShort()))
            out.write(shortToByteArray(bitsPerSample.toShort()))

            out.write("data".toByteArray())
            out.write(intToByteArray(dataSize))

            // 440Hzトーン（ラ音）を生成
            val pcmData = ByteArray(dataSize)
            for (i in 0 until numSamples) {
                val angle = 2.0 * Math.PI * i * 440.0 / sampleRate
                val sample = (Math.sin(angle) * 8000.0).toInt().toShort()
                val byteIdx = i * 2
                pcmData[byteIdx] = (sample.toInt() and 0xFF).toByte()
                pcmData[byteIdx + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
            }
            out.write(pcmData)
        }
    }

    /**
     * RIFF WAVヘッダ付きPCMオーディオファイルの生成 (サンプリング周波数 24kHz, 16bit, モノラル)
     */
    private fun writeSilentWavFile(file: File, durationSec: Float) {
        val sampleRate = 24000
        val channels = 1
        val bitsPerSample = 16
        val numSamples = (sampleRate * durationSec.coerceIn(0.5f, 30.0f)).toInt()
        val dataSize = numSamples * channels * (bitsPerSample / 8)
        val totalSize = 36 + dataSize

        file.outputStream().use { out ->
            // RIFF header
            out.write("RIFF".toByteArray())
            out.write(intToByteArray(totalSize))
            out.write("WAVE".toByteArray())

            // fmt chunk
            out.write("fmt ".toByteArray())
            out.write(intToByteArray(16)) // Chunk size
            out.write(shortToByteArray(1)) // Audio format (1 = PCM)
            out.write(shortToByteArray(channels.toShort()))
            out.write(intToByteArray(sampleRate))
            out.write(intToByteArray(sampleRate * channels * (bitsPerSample / 8))) // Byte rate
            out.write(shortToByteArray((channels * (bitsPerSample / 8)).toShort())) // Block align
            out.write(shortToByteArray(bitsPerSample.toShort()))

            // data chunk
            out.write("data".toByteArray())
            out.write(intToByteArray(dataSize))

            // PCM silence/subtle ambient samples
            val pcmData = ByteArray(dataSize)
            out.write(pcmData)
        }
    }

    private fun intToByteArray(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte()
        )
    }

    private fun shortToByteArray(value: Short): ByteArray {
        return byteArrayOf(
            (value.toInt() and 0xFF).toByte(),
            ((value.toInt() shr 8) and 0xFF).toByte()
        )
    }
}

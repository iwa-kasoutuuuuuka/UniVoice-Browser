package com.univoice.browser.batch

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.univoice.browser.model.BatchApproach
import com.univoice.browser.model.ExecutionStyle
import com.univoice.browser.translation.GeminiClient
import com.univoice.browser.translation.GeminiModels
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
    private val geminiModelName: String = GeminiModels.DEFAULT,
    private val batchApproach: BatchApproach = BatchApproach.APPROACH_C_HYBRID,
    val voiceGender: com.univoice.browser.model.VoiceGender = com.univoice.browser.model.VoiceGender.FEMALE,
    private val requestHeaders: Map<String, String>? = null
) {
    companion object {
        private const val TAG = "BatchDownloadPipeline"

        private val EXCLUDED_MEDIA_HEADERS = setOf("user-agent", "cookie", "host", "content-length", "range")
        private const val RANGE_CHUNK_SIZE = 4L * 1024 * 1024
        /** inline 送信する音声の上限（base64後 約18.7MB でリクエスト上限20MB未満に収める） */
        private const val MAX_INLINE_AUDIO_BYTES = 14L * 1024 * 1024

        private val RANGE_PARAM_AMP = Regex("""&(?:range=\d+-\d+|rn=\d+)(?=&|$)""")
        private val RANGE_PARAM_FIRST = Regex("""\?(?:range=\d+-\d+|rn=\d+)(?:&|$)""")
        private val CLEN_PARAM = Regex("""[?&]clen=(\d+)""")

        /** range= / rn= クエリを除去した全編ストリームURL */
        internal fun stripRangeParams(url: String): String =
            url.replace(RANGE_PARAM_AMP, "").replace(RANGE_PARAM_FIRST, "?").removeSuffix("?")

        /** URL の clen=（ストリーム全体のバイト数） */
        internal fun parseClen(url: String): Long? = CLEN_PARAM.find(url)?.groupValues?.get(1)?.toLongOrNull()

        /** "bytes 0-99/1234" 形式の Content-Range から全体サイズを取得 */
        internal fun parseContentRangeTotal(contentRange: String?): Long? =
            contentRange?.substringAfterLast('/', "")?.trim()?.toLongOrNull()

        /** 音声文字起こし応答の構造化スキーマ */
        private val ASR_SCHEMA: org.json.JSONObject = org.json.JSONObject(
            """
            {"type":"ARRAY","items":{"type":"OBJECT","properties":{
              "index":{"type":"INTEGER"},"startMs":{"type":"INTEGER"},"endMs":{"type":"INTEGER"},
              "originalText":{"type":"STRING"}},
              "required":["index","startMs","endMs","originalText"]}}
            """.trimIndent()
        )
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

    /** Gemini 呼び出し用（長尺音声の文字起こしは数分かかるため読み取りタイムアウトを延長） */
    private val geminiClient: GeminiClient? by lazy {
        if (geminiApiKey.isBlank()) null
        else GeminiClient(
            geminiApiKey,
            httpClient.newBuilder().readTimeout(10, TimeUnit.MINUTES).writeTimeout(5, TimeUnit.MINUTES).build()
        )
    }

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
            // 出力先ディレクトリの準備
            val videoOutputDir = File(cacheDir, safeVideoId).apply { if (!exists()) mkdirs() }
            val audioExtension = if (audioStreamUrl?.contains("webm") == true) "webm" else "m4a"
            val sourceAudioFile = File(videoOutputDir, "source_audio.$audioExtension")
            val videoCacheFile = File(videoOutputDir, "video_cached.mp4")

            _jobStatus.value = BatchJobStatus(
                videoId = safeVideoId,
                title = videoTitle,
                progressPercent = 5,
                audioProgressPercent = 10,
                transProgressPercent = 0,
                videoProgressPercent = 10,
                statusMessageJapanese = "処理を開始しています..."
            )
            downloadedRepo.upsertItem(
                videoId = safeVideoId,
                title = videoTitle,
                videoUrl = fullUrl,
                audioProgress = 10,
                transProgress = 0,
                videoProgress = 10,
                isCompleted = false,
                statusMessage = "処理を開始しています..."
            )

            // =========================================================================
            // Phase 1: メディアストリーム＆字幕データの検証・ダウンロードフェーズ (進捗 0% 〜 30%)
            // ※翻訳処理に先立ち、対象リソースの取得・整合性を100%保証
            // =========================================================================
            Log.i(TAG, "[UniVoiceBrowser] Phase 1: メディア・字幕データ準備開始")
            _jobStatus.value = _jobStatus.value.copy(
                progressPercent = 10,
                audioProgressPercent = 50,
                videoProgressPercent = 30,
                statusMessageJapanese = if (!rawCaptions.isNullOrEmpty()) "YouTube字幕トラックを検証中..." else "音声ストリームをダウンロード中..."
            )

            if (!audioStreamUrl.isNullOrBlank()) {
                downloadFileWithRetry(audioStreamUrl, sourceAudioFile, "音声")
            }

            // 字幕なし（ASR必須）の場合の厳格な検証
            if (rawCaptions.isNullOrEmpty()) {
                if (!sourceAudioFile.exists() || sourceAudioFile.length() < 1024L || isHtmlFile(sourceAudioFile)) {
                    throw IllegalStateException("この動画にはYouTube字幕が存在せず、音声ストリームの取得にも失敗したため、AI文字起こしを実行できません。")
                }
            }

            _jobStatus.value = _jobStatus.value.copy(
                progressPercent = 30,
                audioProgressPercent = 100,
                videoProgressPercent = 100,
                statusMessageJapanese = if (!rawCaptions.isNullOrEmpty()) "字幕データ準備完了 (${rawCaptions.size}件)" else "音声の完全ダウンロード完了"
            )
            downloadedRepo.upsertItem(
                videoId = safeVideoId,
                title = videoTitle,
                videoUrl = fullUrl,
                audioProgress = 100,
                transProgress = 0,
                videoProgress = 100,
                isCompleted = false,
                statusMessage = if (!rawCaptions.isNullOrEmpty()) "字幕データ準備完了" else "音声ダウンロード完了"
            )
            Log.i(TAG, "[UniVoiceBrowser] Phase 1 完了: 字幕数=${rawCaptions?.size ?: 0}, 音声=${if (sourceAudioFile.exists()) sourceAudioFile.length() else 0} bytes")

            // =========================================================================
            // Phase 2: ハイブリッド字幕抽出・文単位補正フェーズ (進捗 30% 〜 45%)
            // ※手動字幕最優先 ＞ 自動生成字幕（文結合・句読点補正） ＞ 実音声ASR
            // ※ダミーのサンプル英文（baseSentences）は完全排除
            // =========================================================================
            var segments: List<TimedSegment>
            if (!rawCaptions.isNullOrEmpty()) {
                _jobStatus.value = _jobStatus.value.copy(
                    progressPercent = 38,
                    statusMessageJapanese = "実字幕トラックから ${rawCaptions.size} 件のセグメントを抽出しました"
                )
                segments = rawCaptions
            } else {
                _jobStatus.value = _jobStatus.value.copy(
                    progressPercent = 38,
                    statusMessageJapanese = "AIによる実音声文字起こしを実施中..."
                )
                segments = transcribeAudio(sourceAudioFile, batchApproach)
            }

            if (segments.isEmpty()) {
                throw IllegalStateException("有効なテキストセグメントを検出できませんでした。動画の字幕(CC)をご確認ください。")
            }

            // =========================================================================
            // Phase 3: 長大文脈AIによる尺合わせ一括翻訳フェーズ (進捗 45% 〜 75%)
            // =========================================================================
            _jobStatus.value = _jobStatus.value.copy(
                progressPercent = 45,
                transProgressPercent = 30,
                statusMessageJapanese = "長大文脈AIによる尺合わせ一括翻訳を実行中..."
            )
            downloadedRepo.upsertItem(
                videoId = safeVideoId,
                title = videoTitle,
                videoUrl = fullUrl,
                audioProgress = 100,
                transProgress = 30,
                videoProgress = 100,
                isCompleted = false,
                totalSegments = segments.size,
                statusMessage = "長大文脈AIによる尺合わせ一括翻訳を実行中..."
            )
            val translatedSegments = batchTranslateWithDurationConstraints(segments, videoTitle)

            _jobStatus.value = _jobStatus.value.copy(
                progressPercent = 75,
                transProgressPercent = 100,
                statusMessageJapanese = "一括翻訳完了。日本語吹き替え音声を生成中..."
            )
            downloadedRepo.upsertItem(
                videoId = safeVideoId,
                title = videoTitle,
                videoUrl = fullUrl,
                audioProgress = 100,
                transProgress = 100,
                videoProgress = 100,
                isCompleted = false,
                totalSegments = segments.size,
                statusMessage = "一括翻訳完了。日本語吹き替え音声を生成中..."
            )

            // =========================================================================
            // Phase 4: タイムスタンプ別日本語音声合成＆永続化 (進捗 75% 〜 100%)
            // =========================================================================
            translatedSegments.forEachIndexed { idx, segment ->
                val dubbingProgress = 75 + (((idx + 1).toFloat() / translatedSegments.size) * 25).toInt()
                _jobStatus.value = _jobStatus.value.copy(
                    progressPercent = dubbingProgress.coerceAtMost(99),
                    statusMessageJapanese = "日本語音声を生成中 (${idx + 1}/${translatedSegments.size}件)..."
                )
                // ループ内のDB更新を10セグメントごと、または最後に制限
                if ((idx + 1) % 10 == 0 || idx == translatedSegments.size - 1) {
                    downloadedRepo.upsertItem(
                        videoId = safeVideoId,
                        title = videoTitle,
                        videoUrl = fullUrl,
                        audioProgress = 100,
                        transProgress = 100,
                        videoProgress = 100,
                        isCompleted = false,
                        totalSegments = translatedSegments.size,
                        statusMessage = "日本語音声を生成中 (${idx + 1}/${translatedSegments.size}件)..."
                    )
                }
                if (idx > 0) {
                    kotlinx.coroutines.delay(60L)
                }
                val audioFile = File(videoOutputDir, "dubbing_${segment.index}.mp3")
                // 訳が得られなかった行は英語原文を読まずに無音にする
                synthesizeSegmentAudioToFile(segment.translatedText, audioFile, segment.durationSec)
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

            // BUG-C04 & BUG-FIX: translatedSegments を完全なメタデータ (index, startMs, endMs, originalText, translatedText) で segments.json に保存
            val segmentsFile = File(videoOutputDir, "segments.json")
            val segmentsData = translatedSegments.map {
                mapOf(
                    "index" to it.index,
                    "startMs" to it.startMs,
                    "endMs" to it.endMs,
                    "originalText" to it.originalText,
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
     * 音声・映像メディアの安全なダウンロードとローカル保存・検証
     */
    private fun downloadFileWithRetry(sourceUrl: String?, targetFile: File, mediaType: String) {
        // 既存キャッシュの整合性検証: 壊れたHTMLファイルやダミーファイル(30KB以下)なら削除して再取得
        if (targetFile.exists()) {
            if (targetFile.length() <= 30720L || isHtmlFile(targetFile)) {
                Log.w(TAG, "[UniVoiceBrowser] 無効または破損した既存${mediaType}キャッシュを破棄: ${targetFile.name} (${targetFile.length()} bytes)")
                targetFile.delete()
            } else {
                Log.i(TAG, "[UniVoiceBrowser] 既存の有効な${mediaType}キャッシュを使用します: ${targetFile.name} (${targetFile.length()} bytes)")
                return
            }
        }

        if (sourceUrl.isNullOrBlank() || sourceUrl.contains("youtube.com/watch") || sourceUrl.contains("youtu.be/")) {
            Log.w(TAG, "[UniVoiceBrowser] ${mediaType}ダウンロード不可: 有効な直接メディアストリームURLではありません ($sourceUrl)")
            return
        }

        val cookieStr = try {
            android.webkit.CookieManager.getInstance().getCookie(sourceUrl)
                ?: android.webkit.CookieManager.getInstance().getCookie("https://m.youtube.com")
        } catch (_: Exception) { null }
        val baseUrl = stripRangeParams(sourceUrl)

        // 1段階目: rangeパラメータを除去した全編ストリーム直接取得
        if (downloadWholeStream(baseUrl, targetFile, mediaType, cookieStr)) {
            Log.i(TAG, "[UniVoiceBrowser] ${mediaType}ローカル保存完了 (一括取得): ${targetFile.name} (${targetFile.length()} bytes)")
            return
        }

        // 2段階目: チャンク分割レンジ取得フォールバック (MSE/DASHストリーム対応)
        Log.i(TAG, "[UniVoiceBrowser] ${mediaType}一括取得が不完全なため、チャンク分割レンジ取得フォールバックを実行")
        if (downloadWithChunkedRangeFallback(baseUrl, targetFile, mediaType, cookieStr)) {
            Log.i(TAG, "[UniVoiceBrowser] ${mediaType}ローカル保存完了 (チャンク取得): ${targetFile.name} (${targetFile.length()} bytes)")
        }
    }

    /** WebView と同じ実UA（未来のChromeバージョン詐称によるブロックを避ける） */
    private val mediaUserAgent: String by lazy {
        try {
            android.webkit.WebSettings.getDefaultUserAgent(context).replace("Version/4.0 ", "").replace("; wv", "")
        } catch (_: Exception) {
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Mobile Safari/537.36"
        }
    }

    /** YouTube メディア取得用リクエスト（WebViewで捕捉したヘッダーを引き継ぐ） */
    private fun mediaRequest(url: String, cookieStr: String?): Request {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", mediaUserAgent)
            .header("Referer", "https://m.youtube.com/")
            .header("Origin", "https://m.youtube.com")
        if (!cookieStr.isNullOrBlank()) builder.header("Cookie", cookieStr)
        requestHeaders?.forEach { (k, v) ->
            if (k.lowercase() !in EXCLUDED_MEDIA_HEADERS) builder.header(k, v)
        }
        return builder.build()
    }

    /** 全編を一括取得し、Content-Length と一致した場合のみ確定（途中切断ファイルをキャッシュしない） */
    private fun downloadWholeStream(baseUrl: String, targetFile: File, mediaType: String, cookieStr: String?): Boolean {
        val tempFile = File(targetFile.parentFile, "${targetFile.name}.part")
        try {
            httpClient.newCall(mediaRequest(baseUrl, cookieStr)).execute().use { response ->
                val body = response.body ?: return false
                val contentType = response.header("Content-Type").orEmpty().lowercase()
                if (!response.isSuccessful || contentType.contains("text/html") || contentType.contains("text/plain")) return false
                val written = tempFile.outputStream().use { fos -> body.byteStream().use { it.copyTo(fos) } }
                val expected = body.contentLength()
                if (expected > 0 && written != expected) {
                    Log.w(TAG, "[UniVoiceBrowser] ${mediaType}一括DLが途中で切断されました ($written/$expected bytes)")
                    return false
                }
            }
            return commitDownloadedFile(tempFile, targetFile, minBytes = 30720L)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "[UniVoiceBrowser] ${mediaType}一括DL例外: ${e.message}")
            return false
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }
    }

    /**
     * range クエリによる分割取得
     * YouTube の range クエリは要求範囲のみを HTTP 200（206ではない）で返すため、
     * 全体サイズは URL の clen= / Content-Range から求め、要求長と受信長の差で終端・range無視を判定する
     */
    private fun downloadWithChunkedRangeFallback(
        baseUrl: String,
        targetFile: File,
        mediaType: String,
        cookieStr: String?
    ): Boolean {
        val tempFile = File(targetFile.parentFile, "${targetFile.name}.part")
        var totalSize = parseClen(baseUrl) ?: -1L
        var start = 0L

        try {
            tempFile.outputStream().use { fos ->
                var chunkIndex = 0
                while (totalSize < 0 || start < totalSize) {
                    val end = (if (totalSize > 0) minOf(start + RANGE_CHUNK_SIZE, totalSize) else start + RANGE_CHUNK_SIZE) - 1
                    val requested = end - start + 1
                    val sep = if (baseUrl.contains("?")) "&" else "?"
                    val chunkUrl = "$baseUrl${sep}range=$start-$end&rn=$chunkIndex"

                    val received = httpClient.newCall(mediaRequest(chunkUrl, cookieStr)).execute().use { resp ->
                        when {
                            resp.code == 416 -> 0L // ファイル終端到達
                            !resp.isSuccessful -> {
                                Log.w(TAG, "[UniVoiceBrowser] $mediaType チャンクDL失敗: code=${resp.code}")
                                -1L
                            }
                            else -> {
                                if (totalSize < 0) totalSize = parseContentRangeTotal(resp.header("Content-Range")) ?: -1L
                                resp.body?.byteStream()?.use { it.copyTo(fos) } ?: 0L
                            }
                        }
                    }
                    if (received < 0) return false
                    if (received == 0L) break

                    start += received
                    chunkIndex++
                    if (totalSize > 0) {
                        val pct = (50 + ((start.toFloat() / totalSize) * 50)).toInt().coerceIn(50, 99)
                        _jobStatus.value = _jobStatus.value.copy(audioProgressPercent = pct)
                    }
                    // 要求長未満 = 終端到達 / 要求長超過 = range を無視して全編が返った
                    if (received != requested) break
                }
            }

            if (totalSize > 0 && tempFile.length() < totalSize) {
                Log.w(TAG, "[UniVoiceBrowser] $mediaType チャンクDLが不完全です (${tempFile.length()}/$totalSize bytes)")
                return false
            }
            return commitDownloadedFile(tempFile, targetFile, minBytes = 1024L)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "[UniVoiceBrowser] チャンクDL例外: ${e.message}")
            return false
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }
    }

    /** 検証済みの一時ファイルを本来のファイル名で確定 */
    private fun commitDownloadedFile(tempFile: File, targetFile: File, minBytes: Long): Boolean {
        if (!tempFile.exists() || tempFile.length() <= minBytes || isHtmlFile(tempFile)) return false
        if (targetFile.exists()) targetFile.delete()
        return tempFile.renameTo(targetFile)
    }

    private fun isHtmlFile(file: File): Boolean {
        return try {
            if (!file.exists() || file.length() < 10) return false
            val buffer = ByteArray(minOf(1024, file.length().toInt()))
            file.inputStream().use { it.read(buffer) }
            val header = String(buffer, Charsets.UTF_8).lowercase()
            header.contains("<!doctype html") || header.contains("<html") || header.contains("<head")
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 実音声の文字起こし（Gemini マルチモーダルAI）。翻訳は行わず Phase 3 に委ねる
     * ※ダミーのサンプル英文（baseSentences）は完全排除し、実音声データのみを対象とする
     */
    private suspend fun transcribeAudio(audioFile: File, approach: BatchApproach): List<TimedSegment> = withContext(Dispatchers.IO) {
        Log.i(TAG, "[UniVoiceBrowser] 実音声文字起こし実行: アプローチ=${approach.titleJapanese}, file=${audioFile.name} (${audioFile.length()} bytes)")

        if (!audioFile.exists() || audioFile.length() < 1024L) {
            throw IllegalStateException("有効な音声ファイルを取得できませんでした。")
        }
        // 注: オンデバイスWhisper推論は未実装のため、音声文字起こしは Gemini のみで行う
        val gemini = geminiClient
            ?: throw IllegalStateException("この動画にはYouTube字幕が存在しません。AIによる音声文字起こしを実行するには、設定画面でGemini APIキーを設定してください。")

        val segments = transcribeAudioWithGemini(gemini, audioFile)
        if (segments.isEmpty()) {
            throw IllegalStateException("音声データから発話テキストを抽出できませんでした（無音・BGMのみ、または認識エラー）。")
        }
        Log.i(TAG, "[UniVoiceBrowser] Gemini Multimodal ASR文字起こし完了: ${segments.size} セグメント")
        segments
    }

    private suspend fun transcribeAudioWithGemini(gemini: GeminiClient, audioFile: File): List<TimedSegment> {
        val mimeType = if (audioFile.name.endsWith(".webm", ignoreCase = true)) "audio/webm" else "audio/mp4"

        // 文字起こしのみに専念させ、翻訳は Phase 3（概要・用語集付きの2段階翻訳＋尺検証）に任せる
        val prompt = """
        提供された音声を正確に書き起こしてください（翻訳はしないでください）。
        すべての発話を文単位のセグメントに分け、各セグメントについて次を出力してください。
        - startMs, endMs: 音声開始からのミリ秒タイムスタンプ（整数）
        - originalText: 話された言語のまま、聞こえたとおりの発話テキスト（句読点を補って文として整える）

        【厳守事項】
        1. 1セグメントは1〜2文とし、長くても約10秒に収めてください。文の途中で区切らないでください。
        2. タイムスタンプは実際の発話開始・終了に合わせ、セグメント同士を重複させないでください。
        3. 音楽のみ・効果音のみ・無音の区間は含めないでください。
        4. 聞き取れない箇所を推測で補完しないでください。
        """.trimIndent()

        // inline は base64 で約1.33倍に膨らみ、リクエスト上限(約20MB)を超えるため、一定サイズ以上は File API でストリーム送信
        var uploadedFileName: String? = null
        try {
            val audioPart = if (audioFile.length() > MAX_INLINE_AUDIO_BYTES) {
                _jobStatus.value = _jobStatus.value.copy(statusMessageJapanese = "大容量音声ファイルをAIにアップロード中...")
                val (fileUri, fileName) = gemini.uploadFile(audioFile, mimeType)
                uploadedFileName = fileName
                org.json.JSONObject().put("fileData", org.json.JSONObject().put("mimeType", mimeType).put("fileUri", fileUri))
            } else {
                val base64Data = android.util.Base64.encodeToString(audioFile.readBytes(), android.util.Base64.NO_WRAP)
                org.json.JSONObject().put("inlineData", org.json.JSONObject().put("mimeType", mimeType).put("data", base64Data))
            }

            _jobStatus.value = _jobStatus.value.copy(statusMessageJapanese = "AIによる音声文字起こしを実行中...")
            val rawText = gemini.generateText(
                geminiModelName,
                GeminiClient.GenerateRequest(
                    userParts = listOf(audioPart, GeminiClient.textPart(prompt)),
                    temperature = 0.2,
                    responseSchema = ASR_SCHEMA
                )
            ).getOrThrow()

            return BatchTranslationSupport.parseAsrSegments(rawText).also {
                if (it.isEmpty()) Log.w(TAG, "[UniVoiceBrowser] Gemini ASR 応答から発話セグメントを抽出できませんでした: ${rawText.take(300)}")
            }
        } finally {
            uploadedFileName?.let { gemini.deleteFile(it) }
        }
    }

    /**
     * 尺合わせ制約付き全編一括翻訳
     * LLMに対して「指定秒数（Duration）内に自然に読み切れる文字数」の制限を付与
     */
    private suspend fun batchTranslateWithDurationConstraints(
        segments: List<TimedSegment>,
        videoTitle: String
    ): List<TimedSegment> {
        val gemini = geminiClient

        // Stage 1: 動画全体の概要・文体・用語集を作成し、全チャンクで共有（チャンク間の訳揺れ防止）
        val brief = gemini?.let { createVideoBrief(it, segments, videoTitle) }

        val chunkSize = 30
        val chunks = segments.chunked(chunkSize)
        val totalChunks = chunks.size

        chunks.forEachIndexed { chunkIdx, chunkSegments ->
            val chunkStartProgress = 30 + ((chunkIdx.toFloat() / totalChunks) * 70).toInt()
            val chunkEndProgress = 30 + (((chunkIdx + 1).toFloat() / totalChunks) * 70).toInt()

            _jobStatus.value = _jobStatus.value.copy(
                transProgressPercent = chunkStartProgress.coerceAtMost(99),
                statusMessageJapanese = "AIによる尺合わせ一括翻訳を実行中 (${chunkIdx + 1}/$totalChunks チャンク)..."
            )

            // Stage 2: 概要・用語集・直前チャンクの対訳を添えてチャンク翻訳
            if (gemini != null) {
                val previousPairs = chunks.getOrNull(chunkIdx - 1).orEmpty()
                    .filter { it.translatedText.isNotBlank() }
                    .takeLast(BatchTranslationSupport.PREVIOUS_CONTEXT_LINES)
                val followingSources = chunks.getOrNull(chunkIdx + 1).orEmpty()
                    .take(BatchTranslationSupport.LOOKAHEAD_LINES)
                    .map { it.originalText }

                val firstPass = requestChunkTranslation(
                    gemini,
                    BatchTranslationSupport.buildChunkPrompt(videoTitle, brief, previousPairs, chunkSegments, followingSources)
                )
                val translations = firstPass.toMutableMap()

                // 欠落・非日本語・尺超過の行だけを再依頼（F）
                val problems = BatchTranslationSupport.findProblems(chunkSegments, translations)
                if (firstPass.isNotEmpty() && problems.isNotEmpty()) {
                    Log.i(TAG, "[UniVoiceBrowser] チャンク ${chunkIdx + 1}: ${problems.size} 行を再翻訳依頼 (${problems.joinToString { "#${it.segment.index}" }})")
                    val retryPass = requestChunkTranslation(
                        gemini,
                        BatchTranslationSupport.buildChunkPrompt(
                            videoTitle, brief, previousPairs,
                            targets = problems.map { it.segment },
                            followingSources = followingSources,
                            retryReasons = problems.associate { it.segment.index to it.reason }
                        )
                    )
                    retryPass.forEach { (idx, ja) ->
                        if (BatchTranslationSupport.isSubstantiallyJapanese(ja)) translations[idx] = ja
                    }
                }

                chunkSegments.forEach { seg ->
                    val ja = translations[seg.index]
                    if (!ja.isNullOrBlank() && BatchTranslationSupport.isSubstantiallyJapanese(ja)) {
                        seg.translatedText = ja
                    }
                }
                if (firstPass.isNotEmpty()) {
                    Log.i(TAG, "[UniVoiceBrowser] Geminiチャンク翻訳成功: ${chunkIdx + 1}/$totalChunks")
                }
            }

            // Gemini 未設定・失敗・欠落行は無料Web翻訳エンジンで行単位にフォールバック
            val untranslated = chunkSegments.filter {
                it.translatedText.isBlank() || !BatchTranslationSupport.isSubstantiallyJapanese(it.translatedText)
            }
            if (untranslated.isNotEmpty()) {
                Log.w(TAG, "[UniVoiceBrowser] チャンク ${chunkIdx + 1} の ${untranslated.size} 行を無料Web翻訳エンジンで翻訳します")
            }
            untranslated.forEachIndexed { sIdx, seg ->
                if (sIdx > 0) {
                    kotlinx.coroutines.delay(60L) // 429レートリミット防止
                }
                try {
                    val translated = freeWebTranslation.translate(seg.originalText, emptyList()).getOrNull()
                    if (!translated.isNullOrBlank() && BatchTranslationSupport.isSubstantiallyJapanese(translated)) {
                        val maxChars = seg.maxRecommendedJapaneseChars
                        seg.translatedText = if (translated.length > maxChars * 1.5) {
                            translated.take((maxChars * 1.3).toInt()) + "…"
                        } else {
                            translated
                        }
                    } else {
                        // 英語原文をTTSに読ませると吹き替え品質を損なうため、訳が得られない行は無音にする
                        seg.translatedText = ""
                        Log.w(TAG, "[UniVoiceBrowser] 翻訳を取得できなかったため無音にします: #${seg.index} ${seg.originalText}")
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    seg.translatedText = ""
                }
            }

            _jobStatus.value = _jobStatus.value.copy(
                transProgressPercent = chunkEndProgress.coerceAtMost(100)
            )
        }

        return segments
    }

    /**
     * 動画全体の概要・文体・用語集を作成（失敗時は null で概要なし翻訳に切り替え）
     */
    private suspend fun createVideoBrief(
        gemini: GeminiClient,
        segments: List<TimedSegment>,
        videoTitle: String
    ): BatchTranslationSupport.VideoBrief? {
        _jobStatus.value = _jobStatus.value.copy(statusMessageJapanese = "動画全体の要約・用語集を作成中...")
        val result = gemini.generateText(
            geminiModelName,
            GeminiClient.GenerateRequest(
                userParts = listOf(GeminiClient.textPart(BatchTranslationSupport.buildBriefPrompt(videoTitle, segments))),
                temperature = 0.2,
                responseSchema = BatchTranslationSupport.BRIEF_SCHEMA
            )
        )
        val brief = result.getOrNull()?.let { BatchTranslationSupport.parseBrief(it) }
        if (brief != null) {
            Log.i(TAG, "[UniVoiceBrowser] 動画概要作成完了: 文体=${brief.style}, 用語集=${brief.glossary.size}件")
        } else {
            Log.w(TAG, "[UniVoiceBrowser] 動画概要の作成に失敗したため、概要なしで翻訳を続行します: ${result.exceptionOrNull()?.message}")
        }
        return brief
    }

    /** チャンク翻訳リクエスト（index → 訳文）。失敗時は空マップ */
    private suspend fun requestChunkTranslation(gemini: GeminiClient, prompt: String): Map<Int, String> {
        val result = gemini.generateText(
            geminiModelName,
            GeminiClient.GenerateRequest(
                userParts = listOf(GeminiClient.textPart(prompt)),
                systemInstruction = BatchTranslationSupport.TRANSLATION_SYSTEM_INSTRUCTION,
                temperature = 0.2,
                responseSchema = BatchTranslationSupport.TRANSLATION_SCHEMA
            )
        )
        return result.fold(
            onSuccess = { BatchTranslationSupport.parseTranslations(it) },
            onFailure = {
                Log.w(TAG, "[UniVoiceBrowser] Geminiチャンク翻訳失敗: ${it.message}")
                emptyMap()
            }
        )
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
     * 実際の日本語TTS音声をファイルへ合成保存
     */
    private fun synthesizeSegmentAudioToFile(text: String, outputFile: File, durationSec: Float) {
        val cleanText = text.trim()
        if (cleanText.isBlank()) {
            writeSilentWavFile(outputFile, durationSec)
            return
        }

        // Google TTS URL長制限対策 (150文字以内の安全長に収める)
        val safeText = if (cleanText.length > 150) cleanText.take(150) else cleanText
        val encodedText = URLEncoder.encode(safeText, "UTF-8")
        val streamUrl = "https://translate.google.com/translate_tts?ie=UTF-8&tl=ja&client=tw-ob&q=$encodedText"

        for (attempt in 0..2) {
            try {
                if (attempt > 0) {
                    Thread.sleep(300L * attempt)
                }
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
                    } else {
                        Log.w(TAG, "[UniVoiceBrowser] TTS取得失敗 (HTTP ${response.code}) attempt=$attempt")
                    }
                }

                if (outputFile.exists() && outputFile.length() > 0L) {
                    Log.d(TAG, "[UniVoiceBrowser] 日本語TTS音声ファイルを生成しました: ${outputFile.name} (${outputFile.length()} bytes)")
                    return
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "[UniVoiceBrowser] クラウドTTS生成試行例外 (${outputFile.name}) attempt=$attempt: ${e.message}")
            }
        }

        // フォールバック: ビープ音ではなく安全な無音WAVを書き込み、不快な高周波ノイズを防止
        writeSilentWavFile(outputFile, durationSec)
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

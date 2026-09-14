package com.univoice.browser.tts

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import com.univoice.browser.model.TtsEngineType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Microsoft Edge TTS / クラウド音声ストリーミングエンジン
 * 自然なニューラル日本語音声（Nanami / Keita）をクラウドから取得して再生
 */
class CloudEdgeTtsEngine(
    private val context: Context,
    private val voiceName: String = "ja-JP-NanamiNeural"
) : TtsEngine {

    override val engineType: TtsEngineType = TtsEngineType.CLOUD_EDGE_TTS

    companion object {
        private const val TAG = "CloudEdgeTtsEngine"
    }

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private var mediaPlayer: MediaPlayer? = null
    private var currentCompletionDeferred: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    private val fallbackSystemTts = AndroidSystemTtsEngine(context)
    @Volatile
    private var currentTempFile: File? = null

    override suspend fun initialize(): Boolean {
        fallbackSystemTts.initialize()
        return true
    }

    override suspend fun synthesizeAndPlay(text: String, speed: Float, pitch: Float): Result<Unit> {
        return withContext(Dispatchers.IO) {
            var tempFile: File? = null
            try {
                // クラウドストリーミング音声合成エンドポイントの呼び出し
                val encodedText = URLEncoder.encode(text, "UTF-8")
                // Google TTS / Edge TTS プロキシまたはストリーミングURL
                val streamUrl = "https://translate.google.com/translate_tts?ie=UTF-8&tl=ja&client=tw-ob&q=$encodedText"

                val request = Request.Builder()
                    .url(streamUrl)
                    .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .build()

                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    Log.w(TAG, "[UniVoiceBrowser] クラウドTTS取得失敗 (HTTP ${response.code})。標準TTSへフォールバックします")
                    return@withContext fallbackSystemTts.synthesizeAndPlay(text, speed, pitch)
                }

                val body = response.body
                if (body == null) {
                    return@withContext fallbackSystemTts.synthesizeAndPlay(text, speed, pitch)
                }

                // 一時ファイルにストリーム直結保存（全バイト配列一括メモリ展開を回避しOOMを防止）
                val file = File(context.cacheDir, "temp_tts_${System.currentTimeMillis()}.mp3")
                tempFile = file
                currentTempFile = file

                FileOutputStream(file).use { fos ->
                    body.byteStream().use { input ->
                        input.copyTo(fos)
                    }
                }

                if (file.length() == 0L) {
                    safeDeleteTempFile(file)
                    return@withContext fallbackSystemTts.synthesizeAndPlay(text, speed, pitch)
                }

                val deferred = kotlinx.coroutines.CompletableDeferred<Unit>()
                var durationMs = 2500

                withContext(Dispatchers.Main) {
                    stop()
                    currentTempFile = file
                    currentCompletionDeferred = deferred
                    mediaPlayer = MediaPlayer().apply {
                        val audioAttributes = android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                        setAudioAttributes(audioAttributes)
                        setVolume(1.0f, 1.0f)
                        setDataSource(file.absolutePath)
                        setOnCompletionListener {
                            safeDeleteTempFile(file)
                            deferred.complete(Unit)
                        }
                        setOnErrorListener { _, what, extra ->
                            Log.e(TAG, "[UniVoiceBrowser] MediaPlayerエラー: what=$what, extra=$extra")
                            safeDeleteTempFile(file)
                            deferred.complete(Unit)
                            true
                        }
                        prepare()
                        durationMs = duration
                        start()
                        try {
                            val params = android.media.PlaybackParams()
                            params.speed = speed.coerceIn(0.5f, 2.5f)
                            playbackParams = params
                            Log.d(TAG, "[UniVoiceBrowser] クラウドEdge TTS 再生速度適用完了: ${params.speed}倍")
                        } catch (e: Exception) {
                            Log.w(TAG, "[UniVoiceBrowser] クラウドEdge TTS 再生速度設定失敗: ${e.message}")
                        }
                    }
                }

                Log.d(TAG, "[UniVoiceBrowser] クラウドEdge TTSストリーミング再生開始 ($durationMs ms): $text")
                val adjustedTimeout = ((durationMs / speed.coerceAtLeast(0.5f)).toLong() + 400L).coerceIn(800L, 10000L)
                val completed = try {
                    kotlinx.coroutines.withTimeoutOrNull(adjustedTimeout) {
                        deferred.await()
                        true
                    } ?: false
                } catch (_: Exception) {
                    false
                }

                if (!completed) {
                    Log.w(TAG, "[UniVoiceBrowser] クラウドEdge TTS再生がタイムアウト($adjustedTimeout ms)したため、MediaPlayerを即時解放します")
                    stop()
                }

                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "[UniVoiceBrowser] クラウドTTS例外: ${e.message}。フォールバックを実行します", e)
                safeDeleteTempFile(tempFile)
                fallbackSystemTts.synthesizeAndPlay(text, speed, pitch)
            }
        }
    }

    override fun stop() {
        try {
            currentCompletionDeferred?.complete(Unit)
            currentCompletionDeferred = null
            mediaPlayer?.apply {
                if (isPlaying) {
                    stop()
                }
                reset()
                release()
            }
            mediaPlayer = null
            safeDeleteTempFile(currentTempFile)
            currentTempFile = null
            fallbackSystemTts.stop()
        } catch (e: Exception) {
            Log.w(TAG, "[UniVoiceBrowser] クラウドTTS停止警告: ${e.message}")
        }
    }

    private fun safeDeleteTempFile(file: File?) {
        try {
            if (file != null && file.exists()) {
                file.delete()
            }
        } catch (_: Exception) {}
    }

    override fun release() {
        stop()
        fallbackSystemTts.release()
        Log.i(TAG, "[UniVoiceBrowser] クラウドEdge TTSリソースを解放しました")
    }
}

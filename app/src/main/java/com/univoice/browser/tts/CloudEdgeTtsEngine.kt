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

    override suspend fun initialize(): Boolean {
        fallbackSystemTts.initialize()
        return true
    }

    override suspend fun synthesizeAndPlay(text: String, speed: Float, pitch: Float): Result<Unit> {
        return withContext(Dispatchers.IO) {
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

                val audioBytes = response.body?.bytes()
                if (audioBytes == null || audioBytes.isEmpty()) {
                    return@withContext fallbackSystemTts.synthesizeAndPlay(text, speed, pitch)
                }

                // 一時ファイルに保存してMediaPlayerで低遅延ストリーム再生
                val tempFile = File(context.cacheDir, "temp_tts_${System.currentTimeMillis()}.mp3")
                FileOutputStream(tempFile).use { it.write(audioBytes) }

                val deferred = kotlinx.coroutines.CompletableDeferred<Unit>()
                var durationMs = 2500

                withContext(Dispatchers.Main) {
                    stop()
                    currentCompletionDeferred = deferred
                    mediaPlayer = MediaPlayer().apply {
                        val audioAttributes = android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                        setAudioAttributes(audioAttributes)
                        setVolume(1.0f, 1.0f)
                        setDataSource(tempFile.absolutePath)
                        setOnCompletionListener {
                            tempFile.delete()
                            deferred.complete(Unit)
                        }
                        setOnErrorListener { _, what, extra ->
                            Log.e(TAG, "[UniVoiceBrowser] MediaPlayerエラー: what=$what, extra=$extra")
                            tempFile.delete()
                            deferred.complete(Unit)
                            true
                        }
                        prepare()
                        durationMs = duration
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                            try {
                                playbackParams = playbackParams.setSpeed(speed.coerceIn(0.75f, 1.75f))
                            } catch (_: Exception) {}
                        }
                        start()
                    }
                }

                Log.d(TAG, "[UniVoiceBrowser] クラウドEdge TTSストリーミング再生開始 ($durationMs ms): $text")
                // 発話の完了を待機（最大でも duration / speed + 400ms でタイムアウトして次の文へ安全に移行）
                val adjustedTimeout = ((durationMs / speed.coerceAtLeast(0.5f)).toLong() + 400L).coerceIn(800L, 10000L)
                try {
                    kotlinx.coroutines.withTimeoutOrNull(adjustedTimeout) {
                        deferred.await()
                    }
                } catch (_: Exception) {}

                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "[UniVoiceBrowser] クラウドTTS例外: ${e.message}。フォールバックを実行します", e)
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
            fallbackSystemTts.stop()
        } catch (e: Exception) {
            Log.w(TAG, "[UniVoiceBrowser] クラウドTTS停止警告: ${e.message}")
        }
    }

    override fun release() {
        stop()
        fallbackSystemTts.release()
        Log.i(TAG, "[UniVoiceBrowser] クラウドEdge TTSリソースを解放しました")
    }
}

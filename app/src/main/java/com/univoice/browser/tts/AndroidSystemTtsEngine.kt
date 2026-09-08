package com.univoice.browser.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.univoice.browser.model.TtsEngineType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Android標準のTextToSpeechエンジンを用いたフォールバック用TTS
 */
class AndroidSystemTtsEngine(private val context: Context) : TtsEngine {

    override val engineType: TtsEngineType = TtsEngineType.ANDROID_SYSTEM

    companion object {
        private const val TAG = "AndroidSystemTtsEngine"
    }

    private var tts: TextToSpeech? = null
    @Volatile
    private var isInitialized = false
    private var initDeferred: CompletableDeferred<Boolean>? = null

    override suspend fun initialize(): Boolean {
        if (isInitialized && tts != null) return true
        return withContext(Dispatchers.Main) {
            try {
                val deferred = CompletableDeferred<Boolean>()
                initDeferred = deferred
                tts = TextToSpeech(context.applicationContext) { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        val result = tts?.setLanguage(Locale.JAPANESE)
                        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                            Log.w(TAG, "[UniVoiceBrowser] 日本語音声データが端末内に未配置です。デフォルト言語にフォールバックして再生を確保します")
                            val defResult = tts?.setLanguage(Locale.getDefault())
                            if (defResult == TextToSpeech.LANG_MISSING_DATA || defResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                                tts?.setLanguage(Locale.US)
                            }
                        } else {
                            Log.i(TAG, "[UniVoiceBrowser] Android標準TTS (日本語) 初期化完了")
                        }
                        val audioAttributes = android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                        tts?.setAudioAttributes(audioAttributes)
                        isInitialized = true
                        deferred.complete(true)
                    } else {
                        Log.e(TAG, "[UniVoiceBrowser] Android標準TTS 初期化ステータスエラー: $status")
                        deferred.complete(false)
                    }
                }
                deferred.await()
            } catch (e: Exception) {
                Log.e(TAG, "[UniVoiceBrowser] Android標準TTS 初期化例外: ${e.message}", e)
                false
            }
        }
    }

    override suspend fun synthesizeAndPlay(text: String, speed: Float, pitch: Float): Result<Unit> {
        return withContext(Dispatchers.Main) {
            if (!isInitialized || tts == null) {
                val ok = initialize()
                if (!ok) {
                    return@withContext Result.failure(IllegalStateException("標準TTSエンジンの初期化に失敗しました"))
                }
            }

            try {
                tts?.setSpeechRate(speed.coerceIn(0.5f, 2.0f))
                tts?.setPitch(pitch.coerceIn(0.5f, 2.0f))

                val utteranceId = "univoice_${System.currentTimeMillis()}"
                val params = Bundle().apply {
                    putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
                    putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, android.media.AudioManager.STREAM_MUSIC)
                }

                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
                val currentVol = audioManager?.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) ?: -1
                Log.d(TAG, "[UniVoiceBrowser] 標準TTS発話開始 (音量=$currentVol): $text")

                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "[UniVoiceBrowser] 標準TTS発話例外: ${e.message}", e)
                Result.failure(e)
            }
        }
    }

    override fun stop() {
        try {
            tts?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "[UniVoiceBrowser] 標準TTS停止エラー: ${e.message}", e)
        }
    }

    override fun release() {
        try {
            tts?.stop()
            tts?.shutdown()
            tts = null
            isInitialized = false
            Log.i(TAG, "[UniVoiceBrowser] 標準TTSリソースを解放しました")
        } catch (e: Exception) {
            Log.e(TAG, "[UniVoiceBrowser] 標準TTS解放エラー: ${e.message}", e)
        }
    }
}

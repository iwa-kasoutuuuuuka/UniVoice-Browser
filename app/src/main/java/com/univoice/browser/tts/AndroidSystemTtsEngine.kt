package com.univoice.browser.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.univoice.browser.model.TtsEngineType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Android標準のTextToSpeechエンジンを用いたフォールバック用TTS
 */
class AndroidSystemTtsEngine(
    private val context: Context,
    var voiceGender: com.univoice.browser.model.VoiceGender = com.univoice.browser.model.VoiceGender.FEMALE
) : TtsEngine {

    override val engineType: TtsEngineType = TtsEngineType.ANDROID_SYSTEM

    companion object {
        private const val TAG = "AndroidSystemTtsEngine"
    }

    private var tts: TextToSpeech? = null
    @Volatile
    private var isInitialized = false
    private var initDeferred: CompletableDeferred<Boolean>? = null

    private var currentDeferred: CompletableDeferred<Unit>? = null
    private val speakMutex = kotlinx.coroutines.sync.Mutex()

    override suspend fun initialize(): Boolean {
        if (isInitialized && tts != null) {
            applyVoiceGender()
            return true
        }
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
                        applyVoiceGender()
                        val audioAttributes = android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                        tts?.setAudioAttributes(audioAttributes)
                        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(utteranceId: String?) {}
                            override fun onDone(utteranceId: String?) {
                                currentDeferred?.complete(Unit)
                            }
                            @Deprecated("Deprecated in Java")
                            override fun onError(utteranceId: String?) {
                                currentDeferred?.complete(Unit)
                            }
                            override fun onError(utteranceId: String?, errorCode: Int) {
                                currentDeferred?.complete(Unit)
                            }
                        })
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

    /**
     * 端末のTextToSpeechインスタンスに男声・女声のVoiceおよびピッチを最適適用
     */
    fun applyVoiceGender() {
        try {
            val systemTts = tts ?: return
            val voices = systemTts.voices
            if (!voices.isNullOrEmpty()) {
                val japaneseVoices = voices.filter { it.locale.language == Locale.JAPANESE.language }
                if (japaneseVoices.isNotEmpty()) {
                    val targetVoice = when (voiceGender) {
                        com.univoice.browser.model.VoiceGender.MALE -> {
                            // 男声の判定: 名前や属性に male, jam, jad, man, 男 などを含むもの
                            japaneseVoices.firstOrNull { v ->
                                val name = v.name.lowercase(Locale.ROOT)
                                name.contains("male") || name.contains("jam") || name.contains("jad") || name.contains("-c-")
                            } ?: japaneseVoices.lastOrNull()
                        }
                        com.univoice.browser.model.VoiceGender.FEMALE -> {
                            // 女声の判定: female, jaf, woman, 女 などを含むもの
                            japaneseVoices.firstOrNull { v ->
                                val name = v.name.lowercase(Locale.ROOT)
                                name.contains("female") || name.contains("jaf") || name.contains("jaa") || name.contains("-a-")
                            } ?: japaneseVoices.firstOrNull()
                        }
                    }
                    if (targetVoice != null) {
                        systemTts.voice = targetVoice
                        Log.i(TAG, "[UniVoiceBrowser] Android標準TTS 音声ボイス設定完了: ${targetVoice.name} (性別: ${voiceGender.titleJapanese})")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "[UniVoiceBrowser] VoiceGender適用警告: ${e.message}")
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
                applyVoiceGender()
                val appliedSpeed = speed.coerceIn(0.5f, 2.5f)
                // 性別に応じたピッチの微調整（男声: 0.88f、女声: 1.08f）
                val basePitch = voiceGender.defaultPitch
                val appliedPitch = (pitch * basePitch).coerceIn(0.5f, 2.0f)
                tts?.setSpeechRate(appliedSpeed)
                tts?.setPitch(appliedPitch)

                val utteranceId = "univoice_${System.currentTimeMillis()}"
                val params = Bundle().apply {
                    putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
                    putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, android.media.AudioManager.STREAM_MUSIC)
                    putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
                }

                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
                val currentVol = audioManager?.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) ?: -1
                Log.d(TAG, "[UniVoiceBrowser] 標準TTS発話開始 (性別=${voiceGender.name}, ピッチ=$appliedPitch, 速度=${appliedSpeed}倍, 音量=$currentVol): $text")

                speakMutex.withLock {
                    currentDeferred?.complete(Unit)
                    val playDeferred = CompletableDeferred<Unit>()
                    currentDeferred = playDeferred

                    tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)

                    val timeoutMs = ((text.length * 220L / appliedSpeed).toLong() + 1500L).coerceIn(800L, 9000L)
                    try {
                        kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
                            playDeferred.await()
                        }
                    } catch (_: Exception) {}
                }

                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "[UniVoiceBrowser] 標準TTS発話例外: ${e.message}", e)
                Result.failure(e)
            }
        }
    }

    override fun stop() {
        try {
            currentDeferred?.complete(Unit)
            currentDeferred = null
            tts?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "[UniVoiceBrowser] 標準TTS停止エラー: ${e.message}", e)
        }
    }

    override fun release() {
        try {
            stop()
            tts?.shutdown()
            tts = null
            isInitialized = false
            Log.i(TAG, "[UniVoiceBrowser] 標準TTSリソースを解放しました")
        } catch (e: Exception) {
            Log.e(TAG, "[UniVoiceBrowser] 標準TTS解放エラー: ${e.message}", e)
        }
    }
}

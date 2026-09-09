package com.univoice.browser.config

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.univoice.browser.model.ProcessingMode
import com.univoice.browser.model.TranslationEngineType
import com.univoice.browser.model.TtsEngineType
import com.univoice.browser.model.UniVoiceSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * UniVoice Browser の設定管理およびモード制御を行うコアマネージャー
 * 4つの動作モードの切り替えおよび永続化を担当
 */
class UniVoiceConfigManager private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _settingsFlow = MutableStateFlow(loadSettings())
    val settingsFlow: StateFlow<UniVoiceSettings> = _settingsFlow.asStateFlow()

    val currentSettings: UniVoiceSettings
        get() = _settingsFlow.value

    companion object {
        private const val TAG = "UniVoiceConfigManager"
        private const val PREFS_NAME = "univoice_browser_preferences"

        // キー定義
        private const val KEY_PROCESSING_MODE = "key_processing_mode"
        private const val KEY_MANUAL_TRANS_ENGINE = "key_manual_trans_engine"
        private const val KEY_MANUAL_TTS_ENGINE = "key_manual_tts_engine"
        private const val KEY_GEMINI_API_KEY = "key_gemini_api_key"
        private const val KEY_GEMINI_MODEL_NAME = "key_gemini_model_name"
        private const val KEY_CUSTOM_ENDPOINT_URL = "key_custom_endpoint_url"
        private const val KEY_HARDWARE_ACCEL = "key_hardware_accel"
        private const val KEY_AUDIO_SUPPRESSION = "key_audio_suppression"
        private const val KEY_SPEECH_SPEED = "key_speech_speed"
        private const val KEY_SPEECH_PITCH = "key_speech_pitch"
        private const val KEY_PREFETCH_COUNT = "key_prefetch_count"
        private const val KEY_AD_BLOCK_ENABLED = "key_ad_block_enabled"
        private const val KEY_BACKGROUND_PLAYBACK_ENABLED = "key_background_playback_enabled"

        @Volatile
        private var instance: UniVoiceConfigManager? = null

        fun getInstance(context: Context): UniVoiceConfigManager {
            return instance ?: synchronized(this) {
                instance ?: UniVoiceConfigManager(context).also { instance = it }
            }
        }
    }

    /**
     * 保存済みの設定を読み込み
     */
    private fun loadSettings(): UniVoiceSettings {
        val modeId = prefs.getString(KEY_PROCESSING_MODE, ProcessingMode.HYBRID_OPTIMAL.id)
        val currentMode = ProcessingMode.fromId(modeId)

        val transEngineId = prefs.getString(KEY_MANUAL_TRANS_ENGINE, TranslationEngineType.GEMINI_CLOUD.id)
        val manualTransEngine = TranslationEngineType.fromId(transEngineId)

        val ttsEngineId = prefs.getString(KEY_MANUAL_TTS_ENGINE, TtsEngineType.LOCAL_VOICEVOX_ONNX.id)
        val manualTtsEngine = TtsEngineType.fromId(ttsEngineId)

        val apiKey = prefs.getString(KEY_GEMINI_API_KEY, "") ?: ""
        val modelName = prefs.getString(KEY_GEMINI_MODEL_NAME, "gemini-1.5-flash") ?: "gemini-1.5-flash"
        val endpoint = prefs.getString(KEY_CUSTOM_ENDPOINT_URL, "") ?: ""
        val hwAccel = prefs.getBoolean(KEY_HARDWARE_ACCEL, true) // Snapdragon 8 Gen 2 / Poco F6 Pro デフォルトON
        val audioSuppression = prefs.getBoolean(KEY_AUDIO_SUPPRESSION, true)
        val speed = prefs.getFloat(KEY_SPEECH_SPEED, 1.0f)
        val pitch = prefs.getFloat(KEY_SPEECH_PITCH, 1.0f)
        val prefetchCount = prefs.getInt(KEY_PREFETCH_COUNT, 3)
        val adBlock = prefs.getBoolean(KEY_AD_BLOCK_ENABLED, true)
        val backgroundPlayback = prefs.getBoolean(KEY_BACKGROUND_PLAYBACK_ENABLED, true)

        return UniVoiceSettings(
            currentMode = currentMode,
            manualTranslationEngine = manualTransEngine,
            manualTtsEngine = manualTtsEngine,
            geminiApiKey = apiKey,
            geminiModelName = modelName,
            customEndpointUrl = endpoint,
            hardwareAcceleration = hwAccel,
            audioSuppressionEnabled = audioSuppression,
            speechSpeed = speed,
            speechPitch = pitch,
            prefetchCount = prefetchCount,
            adBlockEnabled = adBlock,
            backgroundPlaybackEnabled = backgroundPlayback
        )
    }

    /**
     * 設定を更新してSharedPreferencesに保存
     */
    fun updateSettings(newSettings: UniVoiceSettings) {
        prefs.edit().apply {
            putString(KEY_PROCESSING_MODE, newSettings.currentMode.id)
            putString(KEY_MANUAL_TRANS_ENGINE, newSettings.manualTranslationEngine.id)
            putString(KEY_MANUAL_TTS_ENGINE, newSettings.manualTtsEngine.id)
            putString(KEY_GEMINI_API_KEY, newSettings.geminiApiKey)
            putString(KEY_GEMINI_MODEL_NAME, newSettings.geminiModelName)
            putString(KEY_CUSTOM_ENDPOINT_URL, newSettings.customEndpointUrl)
            putBoolean(KEY_HARDWARE_ACCEL, newSettings.hardwareAcceleration)
            putBoolean(KEY_AUDIO_SUPPRESSION, newSettings.audioSuppressionEnabled)
            putFloat(KEY_SPEECH_SPEED, newSettings.speechSpeed)
            putFloat(KEY_SPEECH_PITCH, newSettings.speechPitch)
            putInt(KEY_PREFETCH_COUNT, newSettings.prefetchCount)
            putBoolean(KEY_AD_BLOCK_ENABLED, newSettings.adBlockEnabled)
            putBoolean(KEY_BACKGROUND_PLAYBACK_ENABLED, newSettings.backgroundPlaybackEnabled)
            apply()
        }

        _settingsFlow.value = newSettings
        Log.i(TAG, "[UniVoiceBrowser] 設定を保存しました。現在のモード: ${newSettings.currentMode.titleJapanese}, バックグラウンド再生: ${newSettings.backgroundPlaybackEnabled}")
    }

    /**
     * 動作モードを直接切り替え
     */
    fun setProcessingMode(mode: ProcessingMode) {
        val updated = _settingsFlow.value.copy(currentMode = mode)
        updateSettings(updated)
    }

    /**
     * 広告ブロックのON/OFF切り替え
     */
    fun setAdBlockEnabled(enabled: Boolean) {
        val updated = _settingsFlow.value.copy(adBlockEnabled = enabled)
        updateSettings(updated)
    }

    /**
     * バックグラウンド再生のON/OFF切り替え
     */
    fun setBackgroundPlaybackEnabled(enabled: Boolean) {
        val updated = _settingsFlow.value.copy(backgroundPlaybackEnabled = enabled)
        updateSettings(updated)
    }

    /**
     * デフォルト設定（最適構成 - ハイブリッド推奨モード）にリセット
     */
    fun resetToRecommendedDefaults() {
        val currentApiKey = _settingsFlow.value.geminiApiKey
        val defaults = UniVoiceSettings(
            currentMode = ProcessingMode.HYBRID_OPTIMAL,
            geminiApiKey = currentApiKey, // ユーザーが入力したAPIキーは保持
            hardwareAcceleration = true,
            audioSuppressionEnabled = true,
            speechSpeed = 1.0f,
            speechPitch = 1.0f,
            prefetchCount = 3,
            adBlockEnabled = true
        )
        updateSettings(defaults)
        Log.i(TAG, "[UniVoiceBrowser] 推奨デフォルト設定（最適構成）にリセットしました")
    }
}

package com.univoice.browser.model

/**
 * UniVoice Browser の動作設定モデル
 */
data class UniVoiceSettings(
    val currentMode: ProcessingMode = ProcessingMode.HYBRID_OPTIMAL,
    val manualTranslationEngine: TranslationEngineType = TranslationEngineType.GEMINI_CLOUD,
    val manualTtsEngine: TtsEngineType = TtsEngineType.LOCAL_VOICEVOX_ONNX,
    val geminiApiKey: String = "",
    val geminiModelName: String = "gemini-1.5-flash",
    val customEndpointUrl: String = "",
    val hardwareAcceleration: Boolean = true,
    val audioSuppressionEnabled: Boolean = true,
    val speechSpeed: Float = 1.0f,
    val speechPitch: Float = 1.0f,
    val prefetchCount: Int = 3,
    val adBlockEnabled: Boolean = true,
    val backgroundPlaybackEnabled: Boolean = true
) {
    /**
     * 現在の動作モードに応じた実効翻訳エンジンを取得
     */
    val effectiveTranslationEngine: TranslationEngineType
        get() = when (currentMode) {
            ProcessingMode.PURE_LOCAL -> ProcessingMode.PURE_LOCAL.defaultTranslationEngine
            ProcessingMode.PURE_API -> ProcessingMode.PURE_API.defaultTranslationEngine
            ProcessingMode.HYBRID_OPTIMAL -> ProcessingMode.HYBRID_OPTIMAL.defaultTranslationEngine
            ProcessingMode.MANUAL -> manualTranslationEngine
        }

    /**
     * 現在の動作モードに応じた実効音声合成エンジンを取得
     */
    val effectiveTtsEngine: TtsEngineType
        get() = when (currentMode) {
            ProcessingMode.PURE_LOCAL -> ProcessingMode.PURE_LOCAL.defaultTtsEngine
            ProcessingMode.PURE_API -> ProcessingMode.PURE_API.defaultTtsEngine
            ProcessingMode.HYBRID_OPTIMAL -> ProcessingMode.HYBRID_OPTIMAL.defaultTtsEngine
            ProcessingMode.MANUAL -> manualTtsEngine
        }
}

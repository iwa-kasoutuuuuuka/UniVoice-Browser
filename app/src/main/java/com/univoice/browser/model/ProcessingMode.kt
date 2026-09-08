package com.univoice.browser.model

/**
 * UniVoice Browser の4つの動作モード
 */
enum class ProcessingMode(
    val id: String,
    val titleJapanese: String,
    val descriptionJapanese: String,
    val defaultTranslationEngine: TranslationEngineType,
    val defaultTtsEngine: TtsEngineType
) {
    /**
     * Mode 1: 完全ローカル
     * 端末内LLM (Google AI Edge / MediaPipe) + ローカルAI音声 (VOICEVOX ONNX)
     * Snapdragon 8 Gen 2 NPU/GPUアクセラレーションを強制適用し、完全オフラインで動作
     */
    PURE_LOCAL(
        id = "pure_local",
        titleJapanese = "完全ローカル",
        descriptionJapanese = "端末内AIモデルとローカルAI音声エンジンのみで処理。Snapdragon 8 Gen 2 NPU/GPUを活用した完全オフライン動作",
        defaultTranslationEngine = TranslationEngineType.LOCAL_EDGE,
        defaultTtsEngine = TtsEngineType.LOCAL_VOICEVOX_ONNX
    ),

    /**
     * Mode 2: 完全API
     * クラウド Gemini API + クラウド Edge TTS
     * 高負荷処理をすべてクラウドにオフロードし、端末リソースを節約
     */
    PURE_API(
        id = "pure_api",
        titleJapanese = "完全API",
        descriptionJapanese = "クラウドGemini API翻訳とクラウドEdge TTSストリーミングによる軽量・高精度動作",
        defaultTranslationEngine = TranslationEngineType.GEMINI_CLOUD,
        defaultTtsEngine = TtsEngineType.CLOUD_EDGE_TTS
    ),

    /**
     * Mode 3: 最適構成 (推奨)
     * クラウド Gemini API (高精度文脈翻訳) + ローカルAI音声 (低遅延即時再生)
     * 3字幕先読みバッファにより、ネットワーク音声遅延を完全排除
     */
    HYBRID_OPTIMAL(
        id = "hybrid_optimal",
        titleJapanese = "最適構成",
        descriptionJapanese = "クラウドGemini APIの高精度翻訳と端末内ローカルAI音声のゼロ遅延再生を組み合わせた推奨モード",
        defaultTranslationEngine = TranslationEngineType.GEMINI_CLOUD,
        defaultTtsEngine = TtsEngineType.LOCAL_VOICEVOX_ONNX
    ),

    /**
     * Mode 4: 手動設定
     * ユーザーが翻訳・TTSエンジン、APIキー、エンドポイントを個別にカスタム設定
     */
    MANUAL(
        id = "manual",
        titleJapanese = "手動設定",
        descriptionJapanese = "翻訳エンジン、音声合成エンジン、APIキー、パラメータを自由にカスタマイズ",
        defaultTranslationEngine = TranslationEngineType.GEMINI_CLOUD,
        defaultTtsEngine = TtsEngineType.LOCAL_VOICEVOX_ONNX
    );

    companion object {
        fun fromId(id: String?): ProcessingMode {
            return values().firstOrNull { it.id.equals(id, ignoreCase = true) } ?: HYBRID_OPTIMAL
        }
    }
}

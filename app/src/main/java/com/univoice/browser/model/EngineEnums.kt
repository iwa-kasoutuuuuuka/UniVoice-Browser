package com.univoice.browser.model

/**
 * 翻訳エンジン種別
 */
enum class TranslationEngineType(
    val id: String,
    val titleJapanese: String
) {
    GEMINI_CLOUD("gemini_cloud", "Google Gemini クラウドAPI"),
    LOCAL_EDGE("local_edge", "ローカルAI Edge (Gemma 2B / Llama 3 - NPU加速)");

    companion object {
        fun fromId(id: String?): TranslationEngineType {
            return values().firstOrNull { it.id.equals(id, ignoreCase = true) } ?: GEMINI_CLOUD
        }
    }
}

/**
 * 音声合成 (TTS) エンジン種別
 */
enum class TtsEngineType(
    val id: String,
    val titleJapanese: String
) {
    LOCAL_VOICEVOX_ONNX("local_voicevox_onnx", "ローカルAI音声 (VOICEVOX ONNX - NNAPI加速)"),
    CLOUD_EDGE_TTS("cloud_edge_tts", "Microsoft Edge TTS (クラウドストリーミング)"),
    ANDROID_SYSTEM("android_system", "Android システム標準TTS (軽量フォールバック)");

    companion object {
        fun fromId(id: String?): TtsEngineType {
            return values().firstOrNull { it.id.equals(id, ignoreCase = true) } ?: LOCAL_VOICEVOX_ONNX
        }
    }
}

/**
 * 字幕処理パイプラインのリアルタイムステータス
 */
enum class PipelineStatus(
    val titleJapanese: String
) {
    IDLE("待機中"),
    YOUTUBE_DETECTED("YouTube動画を検出"),
    INTERCEPTING("字幕インターセプト中"),
    TRANSLATING("翻訳中 (先読みバッファ稼働)"),
    SYNTHESIZING("音声合成中"),
    PLAYING("日本語音声再生中"),
    ERROR_FALLBACK("フォールバック処理中");
}

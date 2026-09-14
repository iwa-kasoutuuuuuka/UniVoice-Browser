package com.univoice.browser.model

/**
 * 日本語音声合成の話者性別・トーン
 */
enum class VoiceGender(
    val id: String,
    val titleJapanese: String,
    val edgeVoiceName: String,
    val defaultPitch: Float
) {
    FEMALE(
        id = "female",
        titleJapanese = "女性音声 (自然・明瞭)",
        edgeVoiceName = "ja-JP-NanamiNeural",
        defaultPitch = 1.08f
    ),
    MALE(
        id = "male",
        titleJapanese = "男性音声 (落ち着いた低音)",
        edgeVoiceName = "ja-JP-KeitaNeural",
        defaultPitch = 0.88f
    );

    companion object {
        fun fromId(id: String?): VoiceGender {
            return values().firstOrNull { it.id.equals(id, ignoreCase = true) } ?: FEMALE
        }
    }
}

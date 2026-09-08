package com.univoice.browser.model

/**
 * タイムスタンプ付き字幕チャンクを表すデータクラス
 * @param id 一意な字幕識別子
 * @param startTimeMs 字幕表示開始時間 (ミリ秒)
 * @param endTimeMs 字幕表示終了時間 (ミリ秒)
 * @param originalText 取得元の字幕テキスト (主に英語)
 * @param translatedText 翻訳後の日本語テキスト
 * @param isPrefetched 先読みバッファによって事前翻訳済みかどうか
 */
data class UniVoiceSubtitleCue(
    val id: String = java.util.UUID.randomUUID().toString(),
    val startTimeMs: Long,
    val endTimeMs: Long,
    val originalText: String,
    var translatedText: String? = null,
    var isPrefetched: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
) {
    /**
     * 字幕の表示持続時間 (ミリ秒)
     */
    val durationMs: Long
        get() = (endTimeMs - startTimeMs).coerceAtLeast(1000L)

    /**
     * テキストのサニタイズ（連続改行や空白の除去）
     */
    val cleanText: String
        get() = originalText.replace(Regex("\\s+"), " ").trim()
}

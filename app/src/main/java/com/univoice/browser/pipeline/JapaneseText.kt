package com.univoice.browser.pipeline

/**
 * 日本語テキスト判定の共通ロジック（リアルタイム経路・バッチ経路・翻訳エンジンで同一基準を使う）
 */
object JapaneseText {

    private const val JAPANESE_RATIO_THRESHOLD = 0.35f

    /** 空白を除く文字のうち、かな・漢字が 35% 以上を占めるか */
    fun isSubstantiallyJapanese(text: String): Boolean {
        var total = 0
        var ja = 0
        for (c in text) {
            if (c.isWhitespace()) continue
            total++
            val code = c.code
            if (code in 0x3040..0x30FF || code in 0x4E00..0x9FFF) ja++
        }
        return total > 0 && ja.toFloat() / total >= JAPANESE_RATIO_THRESHOLD
    }
}

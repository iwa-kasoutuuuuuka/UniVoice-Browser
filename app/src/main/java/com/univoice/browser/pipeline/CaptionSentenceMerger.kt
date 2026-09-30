package com.univoice.browser.pipeline

import com.univoice.browser.model.UniVoiceSubtitleCue

/**
 * 文の途中で区切られた字幕断片を、翻訳に適した文単位へ結合する
 *
 * - 文末記号で終わった時点、語数・時間幅が上限に達した時点で確定
 * - 時間的に離れた字幕・シーク（巻き戻し）を検知したら結合せずに区切る
 * - 未完に見える断片（接続詞・前置詞で終わる等）は確定を長めに待ち、完結して見える断片は短く待つ
 *
 * スレッドセーフではないため、呼び出し側で同期すること
 */
class CaptionSentenceMerger(
    private val maxWords: Int = 24,
    private val maxSpanMs: Long = 9000L,
    private val maxGapMs: Long = 2000L
) {
    companion object {
        const val FLUSH_DELAY_COMPLETE_MS = 900L
        const val FLUSH_DELAY_INCOMPLETE_MS = 2600L

        private val WHITESPACE = Regex("\\s+")
        private val SENTENCE_END = Regex("[.?!。？！]['\"”’)]?$")

        /** 文がまだ続くことを示唆する末尾語 */
        private val CONTINUATION_WORDS = setOf(
            "a", "an", "the", "and", "or", "but", "so", "because", "if", "when", "while", "that", "which", "who",
            "to", "of", "in", "on", "at", "for", "with", "from", "by", "about", "into", "like", "than",
            "is", "are", "was", "were", "be", "been", "am", "i", "we", "you", "they", "he", "she", "it",
            "my", "your", "our", "their", "his", "her", "its", "this", "these", "those",
            "going", "gonna", "want", "wanna", "can", "could", "will", "would", "should", "have", "has", "do", "does", "not"
        )

        fun isSentenceEnd(text: String): Boolean = SENTENCE_END.containsMatchIn(text.trim())

        fun looksIncomplete(text: String): Boolean {
            val trimmed = text.trim()
            if (trimmed.isEmpty() || isSentenceEnd(trimmed)) return false
            if (trimmed.endsWith(",") || trimmed.endsWith("、")) return true
            val words = trimmed.split(WHITESPACE)
            if (words.size < 4 && !trimmed.any { it.code in 0x3040..0x9FFF }) return true
            val last = words.last().lowercase().trim { !it.isLetter() }
            return last in CONTINUATION_WORDS
        }
    }

    private val buffer = mutableListOf<UniVoiceSubtitleCue>()

    val hasPending: Boolean get() = buffer.isNotEmpty()

    /**
     * 字幕を追加し、確定した結合済み字幕を返す（0〜2件）
     */
    fun add(cue: UniVoiceSubtitleCue): List<UniVoiceSubtitleCue> {
        val ready = mutableListOf<UniVoiceSubtitleCue>()
        val last = buffer.lastOrNull()
        if (last != null) {
            val isSeekBack = cue.startTimeMs < last.startTimeMs - 1000L
            val gap = cue.startTimeMs - last.endTimeMs
            val span = cue.endTimeMs - buffer.first().startTimeMs
            val isSourceChanged = cue.isTimed != last.isTimed
            if (isSourceChanged || isSeekBack || gap > maxGapMs || span > maxSpanMs) {
                flush()?.let { ready.add(it) }
            }
        }
        buffer.add(cue)

        val merged = mergedText()
        if (isSentenceEnd(merged) || wordCount(merged) >= maxWords) {
            flush()?.let { ready.add(it) }
        }
        return ready
    }

    /** 保留中の断片を強制確定 */
    fun flush(): UniVoiceSubtitleCue? {
        if (buffer.isEmpty()) return null
        val first = buffer.first()
        val merged = if (buffer.size == 1) {
            first
        } else {
            UniVoiceSubtitleCue(
                startTimeMs = first.startTimeMs,
                endTimeMs = buffer.maxOf { it.endTimeMs },
                originalText = mergedText(),
                createdAt = first.createdAt,
                isTimed = first.isTimed
            )
        }
        buffer.clear()
        return merged
    }

    /** 保留中の断片を確定するまでの待機時間 */
    fun suggestedFlushDelayMs(): Long =
        if (looksIncomplete(mergedText())) FLUSH_DELAY_INCOMPLETE_MS else FLUSH_DELAY_COMPLETE_MS

    fun reset() = buffer.clear()

    private fun mergedText(): String = buffer.joinToString(" ") { it.cleanText }.trim()

    private fun wordCount(text: String): Int = if (text.isBlank()) 0 else text.split(WHITESPACE).size
}

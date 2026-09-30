package com.univoice.browser.batch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchTranslationSupportTest {

    private fun seg(index: Int, durationMs: Long, text: String = "source $index", ja: String = "") =
        TimedSegment(index = index, startMs = 0L, endMs = durationMs, originalText = text, translatedText = ja)

    @Test
    fun parseBriefReadsGlossaryAndSkipsEmptyEntries() {
        val raw = """
            {"summary":"ブラウザ開発の解説","style":"です・ます調",
             "glossary":[{"source":"WebView","japanese":"ウェブビュー"},{"source":"","japanese":"x"}]}
        """.trimIndent()
        val brief = BatchTranslationSupport.parseBrief(raw)
        assertNotNull(brief)
        assertEquals("です・ます調", brief!!.style)
        assertEquals(listOf("WebView" to "ウェブビュー"), brief.glossary)
    }

    @Test
    fun parseBriefReturnsNullOnInvalidJson() {
        assertNull(BatchTranslationSupport.parseBrief("not json"))
    }

    @Test
    fun parseTranslationsHandlesFencesAndDropsBlank() {
        val raw = "```json\n[{\"index\":0,\"japanese\":\"こんにちは\"},{\"index\":1,\"japanese\":\"\"},{\"japanese\":\"idなし\"}]\n```"
        assertEquals(mapOf(0 to "こんにちは"), BatchTranslationSupport.parseTranslations(raw))
    }

    @Test
    fun findProblemsDetectsMissingNonJapaneseAndOverlong() {
        val targets = listOf(seg(0, 2000), seg(1, 2000), seg(2, 2000), seg(3, 2000))
        // 2秒 → 14拍（許容 16.8拍）、プロンプト上の目安は10字
        val translations = mapOf(
            0 to "こんにちは。",
            2 to "Hello there",
            3 to "これはとても長すぎる翻訳文なので尺に収まりません"
        )
        val problems = BatchTranslationSupport.findProblems(targets, translations)
        assertEquals(listOf(1, 2, 3), problems.map { it.segment.index })
        assertTrue(problems[2].reason.contains("10字以内"))
    }

    @Test
    fun spokenLengthIgnoresPunctuationButCountsLongVowel() {
        assertEquals(5, BatchTranslationSupport.spokenLength("こんにちは。"))
        assertEquals(5, BatchTranslationSupport.spokenLength("ブラウザー、"))
        assertEquals(4, BatchTranslationSupport.spokenLength("「ブラウザ」"))
    }

    @Test
    fun chunkPromptIncludesBriefContextAndRetryReasons() {
        val brief = BatchTranslationSupport.VideoBrief("概要です", "です・ます調", listOf("WebView" to "ウェブビュー"))
        val previous = listOf(seg(9, 2000, "Previous line", "前の行です"))
        val prompt = BatchTranslationSupport.buildChunkPrompt(
            videoTitle = "Title",
            brief = brief,
            previousPairs = previous,
            targets = listOf(seg(10, 2000, "Target line")),
            followingSources = listOf("Next line"),
            retryReasons = mapOf(10 to "長すぎます")
        )
        assertTrue(prompt.contains("WebView → ウェブビュー"))
        assertTrue(prompt.contains("です・ます調"))
        assertTrue(prompt.contains("EN: Previous line\nJA: 前の行です"))
        assertTrue(prompt.contains("[ID:10]"))
        assertTrue(prompt.contains("前回の問題: 長すぎます"))
        assertTrue(prompt.contains("【後続の原文（参考・翻訳不要）】\nNext line"))
        assertFalse(prompt.contains("[ID:9]"))
    }

    @Test
    fun briefPromptTruncatesVeryLongTranscripts() {
        val longText = "a".repeat(1000)
        val segments = (0 until 100).map { seg(it, 1000, longText) }
        val prompt = BatchTranslationSupport.buildBriefPrompt("T", segments)
        assertTrue(prompt.contains("冒頭部分のみ"))
        assertTrue(prompt.length < BatchTranslationSupport.MAX_BRIEF_SOURCE_CHARS + 1000)
    }
}

package com.univoice.browser.batch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gemini 音声文字起こし応答パーサー（BatchTranslationSupport.parseAsrSegments）のユニットテスト
 */
class GeminiAsrParsingTest {

    @Test
    fun parsesStandardArrayWithoutTranslation() {
        val json = """
        [
          {"index": 0, "startMs": 1200, "endMs": 4500, "originalText": "Hello and welcome to the channel."},
          {"index": 1, "startMs": 4800, "endMs": 8000, "originalText": "Today we are building an Android browser."}
        ]
        """.trimIndent()

        val segments = BatchTranslationSupport.parseAsrSegments(json)
        assertEquals(2, segments.size)
        assertEquals(1200L, segments[0].startMs)
        assertEquals(4500L, segments[0].endMs)
        assertEquals("Hello and welcome to the channel.", segments[0].originalText)
        // 文字起こし段階では翻訳しない（Phase 3 で翻訳する）
        assertTrue(segments.all { it.translatedText.isBlank() })
    }

    @Test
    fun stripsMarkdownFenceAndAcceptsWrappedObject() {
        val raw = "```json\n{\"segments\":[{\"startMs\":0,\"endMs\":1000,\"originalText\":\"Hi.\"}]}\n```"
        val segments = BatchTranslationSupport.parseAsrSegments(raw)
        assertEquals(listOf("Hi."), segments.map { it.originalText })
    }

    @Test
    fun sortsByStartAndReassignsSequentialIndexes() {
        val json = """
        [
          {"index": 5, "startMs": 9000, "endMs": 10000, "originalText": "third"},
          {"index": 5, "startMs": 1000, "endMs": 2000, "originalText": "first"},
          {"index": 2, "startMs": 3000, "endMs": 4000, "originalText": "  "},
          {"index": 9, "startMs": 5000, "endMs": 6000, "originalText": "second"}
        ]
        """.trimIndent()
        val segments = BatchTranslationSupport.parseAsrSegments(json)
        assertEquals(listOf("first", "second", "third"), segments.map { it.originalText })
        assertEquals(listOf(0, 1, 2), segments.map { it.index })
    }

    @Test
    fun fixesInvalidEndTimestamp() {
        val segments = BatchTranslationSupport.parseAsrSegments("""[{"startMs": 5000, "endMs": 4000, "originalText": "x y"}]""")
        assertEquals(8000L, segments[0].endMs)
    }

    @Test
    fun returnsEmptyOnGarbage() {
        assertTrue(BatchTranslationSupport.parseAsrSegments("sorry, I cannot").isEmpty())
        assertTrue(BatchTranslationSupport.parseAsrSegments("[{broken").isEmpty())
    }
}

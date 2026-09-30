package com.univoice.browser.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiTranslationTest {

    @Test
    fun retiredModelsAreReplacedWithDefault() {
        assertEquals(GeminiModels.DEFAULT, GeminiModels.resolveConfigured("gemini-1.5-flash-latest"))
        assertEquals(GeminiModels.DEFAULT, GeminiModels.resolveConfigured("gemini-2.0-flash"))
        assertEquals(GeminiModels.DEFAULT, GeminiModels.resolveConfigured(""))
        assertEquals(GeminiModels.DEFAULT, GeminiModels.resolveConfigured(null))
        assertEquals("gemini-2.5-flash", GeminiModels.resolveConfigured(" gemini-2.5-flash "))
    }

    @Test
    fun candidatesStartWithConfiguredAndHaveNoRetiredOrDuplicates() {
        val candidates = GeminiModels.candidates("gemini-3.5-flash-lite")
        assertEquals("gemini-3.5-flash-lite", candidates.first())
        assertEquals(candidates.distinct(), candidates)
        assertTrue(candidates.none { GeminiModels.isRetired(it) })
    }

    @Test
    fun onlyModelOrServerSideErrorsFallBackToOtherModels() {
        assertTrue(GeminiClient.isRetryableWithOtherModel(404))
        assertTrue(GeminiClient.isRetryableWithOtherModel(429))
        assertTrue(GeminiClient.isRetryableWithOtherModel(503))
        // サイズ超過・不正リクエスト・認証エラーは別モデルでも失敗するため再送しない
        assertFalse(GeminiClient.isRetryableWithOtherModel(400))
        assertFalse(GeminiClient.isRetryableWithOtherModel(401))
        assertFalse(GeminiClient.isRetryableWithOtherModel(413))
    }

    @Test
    fun extractTextSkipsThoughtParts() {
        val body = """
            {"candidates":[{"content":{"parts":[
              {"text":"考え中…","thought":true},
              {"text":"こんにちは、"},
              {"text":"皆さん。"}
            ]}}]}
        """.trimIndent()
        assertEquals("こんにちは、皆さん。", GeminiClient.extractText(body))
    }

    @Test
    fun extractTextReturnsEmptyWhenNoCandidates() {
        assertEquals("", GeminiClient.extractText("""{"promptFeedback":{"blockReason":"SAFETY"}}"""))
    }

    @Test
    fun userPromptContainsTitleAndPreviousPairsInOrder() {
        val context = TranslationContext(
            previousPairs = (1..8).map { "src$it" to "訳$it" },
            videoTitle = "Building a Browser"
        )
        val prompt = CloudGeminiTranslationEngine.buildUserPrompt("next line", context)
        assertTrue(prompt.contains("Building a Browser"))
        // 直近6件のみ（src3〜src8）を古い順に含める
        assertFalse(prompt.contains("EN: src2\n"))
        assertTrue(prompt.indexOf("EN: src3") < prompt.indexOf("EN: src8"))
        assertTrue(prompt.trimEnd().endsWith("next line"))
    }

    @Test
    fun userPromptWithoutContextIsJustTarget() {
        val prompt = CloudGeminiTranslationEngine.buildUserPrompt("hello", TranslationContext())
        assertEquals("【翻訳対象の字幕】\nhello", prompt)
    }

    @Test
    fun cleanOutputStripsWrappingQuotesAndLabels() {
        assertEquals("こんにちは", CloudGeminiTranslationEngine.cleanOutput("「こんにちは」"))
        assertEquals("こんにちは", CloudGeminiTranslationEngine.cleanOutput("JA: こんにちは"))
        assertEquals("こんにちは", CloudGeminiTranslationEngine.cleanOutput("\"こんにちは\""))
        assertEquals("\"A\" と \"B\"", CloudGeminiTranslationEngine.cleanOutput("\"A\" と \"B\""))
        // 文中の括弧は残す
        assertEquals("「A」と「B」", CloudGeminiTranslationEngine.cleanOutput("「A」と「B」"))
    }
}

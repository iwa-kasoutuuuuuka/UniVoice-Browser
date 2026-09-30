package com.univoice.browser.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JapaneseSpeechTimingTest {

    private fun morae(text: String) = JapaneseSpeechTiming.estimateMorae(text)

    @Test
    fun kanaCountsOneMoraEachAndSmallKanaMerges() {
        assertEquals(5f, morae("こんにちは"), 0.001f)
        // きょう = き(ょ) う → 2拍、チャンネル = チ(ャ)ンネル → 4拍
        assertEquals(2f, morae("きょう"), 0.001f)
        assertEquals(4f, morae("チャンネル"), 0.001f)
        // 長音・促音は1拍
        assertEquals(4f, morae("ブラウザー".substring(1)), 0.001f)
        assertEquals(3f, morae("きっと"), 0.001f)
    }

    @Test
    fun punctuationIsSilentAndKanjiIsHeavierThanOneMora() {
        assertEquals(morae("こんにちは"), morae("「こんにちは。」"), 0.001f)
        // 漢字の多い文は文字数より拍数が多い
        val text = "本日開発開始"
        assertTrue(morae(text) > text.length)
    }

    @Test
    fun latinWordsAreEstimated() {
        assertTrue(morae("Android") >= 3f)
    }

    @Test
    fun charLimitIsDerivedFromMoraBudget() {
        // (2秒 - 余白0.35秒) × 5.9拍 ≒ 9.7拍 → 9.7 / 1.3 ≒ 7字
        assertEquals(9.735f, JapaneseSpeechTiming.maxMoraeFor(2f), 0.001f)
        assertEquals(7, JapaneseSpeechTiming.maxCharsFor(2f))
        // 余白より短い尺は 0拍（文字数は下限3字）
        assertEquals(0f, JapaneseSpeechTiming.maxMoraeFor(0.2f), 0.001f)
        assertEquals(3, JapaneseSpeechTiming.maxCharsFor(0.1f))
    }

    @Test
    fun durationScalesWithSpeed() {
        val normal = JapaneseSpeechTiming.estimateDurationSec("こんにちは")
        val fast = JapaneseSpeechTiming.estimateDurationSec("こんにちは", speed = 2f)
        assertEquals(normal / 2f, fast, 0.001f)
    }
}

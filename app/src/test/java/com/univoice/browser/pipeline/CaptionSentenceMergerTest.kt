package com.univoice.browser.pipeline

import com.univoice.browser.model.UniVoiceSubtitleCue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionSentenceMergerTest {

    private fun cue(start: Long, end: Long, text: String) =
        UniVoiceSubtitleCue(startTimeMs = start, endTimeMs = end, originalText = text)

    @Test
    fun mergesFragmentsUntilSentenceEnd() {
        val merger = CaptionSentenceMerger()
        assertTrue(merger.add(cue(0, 2000, "so what we're going to")).isEmpty())
        val ready = merger.add(cue(2000, 4000, "do today is build a browser."))
        assertEquals(1, ready.size)
        assertEquals("so what we're going to do today is build a browser.", ready[0].originalText)
        assertEquals(0L, ready[0].startTimeMs)
        assertEquals(4000L, ready[0].endTimeMs)
        assertFalse(merger.hasPending)
    }

    @Test
    fun completeSentenceIsEmittedImmediately() {
        val merger = CaptionSentenceMerger()
        val ready = merger.add(cue(0, 2000, "Hello everyone!"))
        assertEquals(listOf("Hello everyone!"), ready.map { it.originalText })
    }

    @Test
    fun largeTimeGapSplitsFragments() {
        val merger = CaptionSentenceMerger(maxGapMs = 2000L)
        merger.add(cue(0, 2000, "first part without end"))
        val ready = merger.add(cue(10_000, 12_000, "unrelated later line"))
        assertEquals(listOf("first part without end"), ready.map { it.originalText })
        assertEquals("unrelated later line", merger.flush()?.originalText)
    }

    @Test
    fun seekBackwardSplitsFragments() {
        val merger = CaptionSentenceMerger()
        merger.add(cue(30_000, 32_000, "we were talking about"))
        val ready = merger.add(cue(5_000, 7_000, "an earlier line"))
        assertEquals(listOf("we were talking about"), ready.map { it.originalText })
    }

    @Test
    fun wordLimitForcesEmission() {
        val merger = CaptionSentenceMerger(maxWords = 6)
        merger.add(cue(0, 1000, "one two three"))
        val ready = merger.add(cue(1000, 2000, "four five six"))
        assertEquals(1, ready.size)
        assertEquals("one two three four five six", ready[0].originalText)
    }

    @Test
    fun flushReturnsPendingAndClears() {
        val merger = CaptionSentenceMerger()
        merger.add(cue(0, 1000, "pending fragment here now"))
        assertEquals("pending fragment here now", merger.flush()?.originalText)
        assertNull(merger.flush())
    }

    @Test
    fun incompleteFragmentsWaitLonger() {
        assertTrue(CaptionSentenceMerger.looksIncomplete("I think we should go to the"))
        assertTrue(CaptionSentenceMerger.looksIncomplete("and then,"))
        assertFalse(CaptionSentenceMerger.looksIncomplete("This is the end."))
        assertFalse(CaptionSentenceMerger.looksIncomplete("that is really a great result"))

        val merger = CaptionSentenceMerger()
        merger.add(cue(0, 1000, "we are going to talk about the"))
        assertEquals(CaptionSentenceMerger.FLUSH_DELAY_INCOMPLETE_MS, merger.suggestedFlushDelayMs())
    }

    @Test
    fun japaneseSentenceEndIsDetected() {
        val merger = CaptionSentenceMerger()
        val ready = merger.add(cue(0, 2000, "今日はブラウザを作ります。"))
        assertEquals(1, ready.size)
    }
}

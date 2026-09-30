package com.univoice.browser.batch

import org.junit.Assert.assertEquals
import org.junit.Test

class TimedTextSentenceEndTest {

    @Test
    fun japaneseAutoCaptionGetsJapanesePeriod() {
        assertEquals("君に伝えたいんだ。", YouTubeTimedTextParser.withSentenceEnd("君に伝えたいんだ", isManual = false))
    }

    @Test
    fun englishAutoCaptionGetsAsciiPeriod() {
        assertEquals("never gonna give you up.", YouTubeTimedTextParser.withSentenceEnd("never gonna give you up", isManual = false))
    }

    @Test
    fun existingEndOrManualCaptionIsUnchanged() {
        assertEquals("Really?", YouTubeTimedTextParser.withSentenceEnd("Really?", isManual = false))
        assertEquals("そうだね！", YouTubeTimedTextParser.withSentenceEnd("そうだね！", isManual = false))
        assertEquals("manual line", YouTubeTimedTextParser.withSentenceEnd("manual line", isManual = true))
    }
}

package com.univoice.browser.batch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaUrlParsingTest {

    private val base = "https://r1.googlevideo.com/videoplayback"

    @Test
    fun stripsRangeAndRnParamsAnywhereInQuery() {
        assertEquals("$base?id=1&clen=100", BatchDownloadPipeline.stripRangeParams("$base?id=1&range=0-99&rn=3&clen=100"))
        assertEquals("$base?id=1", BatchDownloadPipeline.stripRangeParams("$base?range=0-99&id=1"))
        assertEquals(base, BatchDownloadPipeline.stripRangeParams("$base?range=0-99"))
        assertEquals("$base?id=1", BatchDownloadPipeline.stripRangeParams("$base?id=1&rn=2"))
        // 他パラメータの一部に "range" を含んでも除去しない
        assertEquals("$base?id=1&xrange=5", BatchDownloadPipeline.stripRangeParams("$base?id=1&xrange=5"))
    }

    @Test
    fun parsesClenAndContentRange() {
        assertEquals(123456L, BatchDownloadPipeline.parseClen("$base?id=1&clen=123456&dur=10"))
        assertNull(BatchDownloadPipeline.parseClen("$base?id=1"))
        assertEquals(5000L, BatchDownloadPipeline.parseContentRangeTotal("bytes 0-99/5000"))
        assertNull(BatchDownloadPipeline.parseContentRangeTotal("bytes 0-99/*"))
        assertNull(BatchDownloadPipeline.parseContentRangeTotal(null))
    }
}

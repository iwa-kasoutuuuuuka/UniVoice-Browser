package com.univoice.browser.pipeline

import com.univoice.browser.model.UniVoiceSubtitleCue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimedPlaybackTest {

    private var wall = 1_000_000L
    private val now = { wall }

    @Test
    fun clockInterpolatesWhilePlayingAndFreezesWhenPaused() {
        val clock = PlaybackClock(now)
        assertFalse(clock.hasPosition)
        clock.update(isPlaying = true, positionMs = 10_000)
        wall += 400
        assertEquals(10_400L, clock.positionMs())

        clock.update(isPlaying = false, positionMs = 10_400)
        wall += 5_000
        assertEquals(10_400L, clock.positionMs())
    }

    @Test
    fun clockAppliesPlaybackRate() {
        val clock = PlaybackClock(now)
        clock.update(true, 0)
        clock.setRate(2.0f)
        wall += 1_000
        assertEquals(2_000L, clock.positionMs())
    }

    @Test
    fun regularUpdatesAreNotSeeksButJumpsAre() {
        val clock = PlaybackClock(now)
        assertFalse(clock.update(true, 10_000))
        wall += 250
        assertFalse(clock.update(true, 10_260))
        wall += 250
        assertTrue("巻き戻しはシーク", clock.update(true, 2_000))
        wall += 250
        assertTrue("早送りもシーク", clock.update(true, 60_000))
    }

    @Test
    fun bufferingStallIsNotMistakenForSeek() {
        val clock = PlaybackClock(now)
        clock.update(true, 10_000)
        // バッファリングで5秒間 timeupdate が止まっても、推定位置は補間上限までしか進まない
        wall += 5_000
        assertEquals(10_000L + PlaybackClock.MAX_EXTRAPOLATION_MS, clock.positionMs())
        // 再開後の通常通知（ほぼ同じ位置）はシーク扱いしない
        assertFalse(clock.update(true, 10_100))
    }

    @Test
    fun seekWhilePausedIsDetected() {
        val clock = PlaybackClock(now)
        clock.update(false, 10_000)
        wall += 60_000
        assertTrue(clock.update(false, 40_000))
    }

    @Test
    fun markingOneCueDoesNotAffectPartiallyOverlappingNeighbour() {
        val registry = TimedCueRegistry(now)
        registry.registerPending(0, 3_000)
        registry.registerPending(2_000, 5_000)
        registry.markDropped(0, 3_000)
        assertFalse(registry.isCoveredByTrack(500))
        // 隣接行（2〜5秒）は読み上げ待ちのまま
        assertTrue(registry.isCoveredByTrack(4_000))
    }

    @Test
    fun stalePendingEntriesExpire() {
        val registry = TimedCueRegistry(now)
        registry.registerPending(0, 3_000)
        wall += 300_000
        assertFalse(registry.isCoveredByTrack(1_000))
    }

    @Test
    fun registrySuppressesDomCuesForPendingAndRecentlySpokenTrackLines() {
        val registry = TimedCueRegistry(now)
        registry.registerPending(10_000, 13_000)
        assertTrue(registry.isCoveredByTrack(11_000))
        assertFalse(registry.isCoveredByTrack(20_000))

        registry.markSpoken(10_000, 13_000)
        assertTrue(registry.isCoveredByTrack(11_000))
        // 読み上げから時間が経てば（シークして再視聴した場合など）DOM字幕を再び通す
        wall += 30_000
        assertFalse(registry.isCoveredByTrack(11_000))
    }

    @Test
    fun droppedTrackLinesDoNotSuppressDomCues() {
        val registry = TimedCueRegistry(now)
        registry.registerPending(10_000, 13_000)
        registry.registerPending(13_000, 16_000)
        registry.dropAllPending()
        assertFalse(registry.isCoveredByTrack(11_000))
        assertFalse(registry.isCoveredByTrack(14_000))
    }

    @Test
    fun markingMergedRangeUpdatesAllOverlappingEntries() {
        val registry = TimedCueRegistry(now)
        registry.registerPending(0, 2_000)
        registry.registerPending(2_000, 4_000)
        registry.markDropped(0, 4_000)
        assertFalse(registry.isCoveredByTrack(1_000))
        assertFalse(registry.isCoveredByTrack(3_000))
    }

    @Test
    fun mergerDoesNotMixTrackAndDomCues() {
        val merger = CaptionSentenceMerger()
        merger.add(UniVoiceSubtitleCue(startTimeMs = 0, endTimeMs = 2000, originalText = "track line going to", isTimed = true))
        val ready = merger.add(UniVoiceSubtitleCue(startTimeMs = 2000, endTimeMs = 4000, originalText = "dom line continues"))
        assertEquals(1, ready.size)
        assertTrue(ready[0].isTimed)
        assertFalse(merger.flush()!!.isTimed)
    }

    @Test
    fun mergedTrackCueKeepsTimedFlagAndRange() {
        val merger = CaptionSentenceMerger()
        merger.add(UniVoiceSubtitleCue(startTimeMs = 1000, endTimeMs = 3000, originalText = "so today we are going to", isTimed = true))
        val ready = merger.add(UniVoiceSubtitleCue(startTimeMs = 3000, endTimeMs = 5000, originalText = "build a browser.", isTimed = true))
        assertEquals(1, ready.size)
        assertTrue(ready[0].isTimed)
        assertEquals(1000L, ready[0].startTimeMs)
        assertEquals(5000L, ready[0].endTimeMs)
    }
}

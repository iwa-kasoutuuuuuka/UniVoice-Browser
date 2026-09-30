package com.univoice.browser.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionTrackFeederTest {

    private fun cue(startSec: Int, text: String = "line $startSec") =
        CaptionTrackFeeder.TrackCue(startSec * 1000L, startSec * 1000L + 2500L, text)

    private val track = (0 until 100 step 5).map { cue(it) } // 0,5,10,...,95秒

    @Test
    fun feedsOnlyWithinLookaheadAndAdvances() {
        val feeder = CaptionTrackFeeder(lookaheadMs = 12_000L)
        assertTrue(feeder.setTrack("vid", "k1", track, positionMs = 0L))
        assertEquals(listOf(0L, 5000L, 10000L), feeder.takeDue(0L).map { it.startMs })
        // 払い出し済みの行は再度返さない
        assertTrue(feeder.takeDue(1_000L).isEmpty())
        assertEquals(listOf(15000L), feeder.takeDue(3_000L).map { it.startMs })
    }

    @Test
    fun startsFromCurrentPositionWhenTrackArrivesMidVideo() {
        val feeder = CaptionTrackFeeder(lookaheadMs = 6_000L)
        feeder.setTrack("vid", "k1", track, positionMs = 41_000L)
        // 40秒の行(40.0-42.5s)はまだ表示中なので対象、それ以前は対象外
        assertEquals(listOf(40000L, 45000L), feeder.takeDue(41_000L).map { it.startMs })
    }

    @Test
    fun seekRewindsAndFastForwards() {
        val feeder = CaptionTrackFeeder(lookaheadMs = 6_000L)
        feeder.setTrack("vid", "k1", track, positionMs = 50_000L)
        feeder.takeDue(50_000L)
        feeder.seekTo(10_000L)
        assertEquals(listOf(10000L, 15000L), feeder.takeDue(10_000L).map { it.startMs })
        feeder.seekTo(90_000L)
        assertEquals(listOf(90000L, 95000L), feeder.takeDue(90_000L).map { it.startMs })
        assertTrue(feeder.takeDue(99_000L).isEmpty())
    }

    @Test
    fun sameTrackRecaptureIsIgnoredButNewTrackReplaces() {
        val feeder = CaptionTrackFeeder(lookaheadMs = 6_000L)
        assertTrue(feeder.setTrack("vid", "k1", track, 0L))
        feeder.takeDue(0L)
        // プレーヤーのCC切替などで同じ本文を再取得しても、払い出し位置は維持
        assertFalse(feeder.setTrack("vid", "k1", track, 0L))
        assertTrue(feeder.takeDue(0L).isEmpty())
        // 別言語トラックに切替わった場合は現在位置から払い出し直す
        assertTrue(feeder.setTrack("vid", "k2", track.map { it.copy(text = "ja") }, 0L))
        assertEquals("ja", feeder.takeDue(0L).first().text)
    }

    @Test
    fun clearRemovesTrack() {
        val feeder = CaptionTrackFeeder()
        feeder.setTrack("vid", "k1", track, 0L)
        feeder.clear()
        assertFalse(feeder.hasTrack)
        assertTrue(feeder.takeDue(0L).isEmpty())
    }
}

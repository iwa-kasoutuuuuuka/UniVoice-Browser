package com.univoice.browser.pipeline

import com.univoice.browser.model.UniVoiceSubtitleCue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechQueueTest {

    private var wall = 1_000_000L
    private val queue = SpeechQueue { wall }

    private fun timed(startMs: Long, text: String = "t$startMs", generation: Int = 0) = SpeechQueue.PreparedCue(
        UniVoiceSubtitleCue(startTimeMs = startMs, endTimeMs = startMs + 3000, originalText = text, isTimed = true),
        text, "訳$text", false, generation
    )

    private fun untimed(videoMs: Long, createdAt: Long = wall, text: String = "d$videoMs") = SpeechQueue.PreparedCue(
        UniVoiceSubtitleCue(startTimeMs = videoMs, endTimeMs = videoMs + 3000, originalText = text, createdAt = createdAt),
        text, "訳$text", false, 0
    )

    private fun state(pos: Long, playing: Boolean = true, generation: Int = 0) =
        SpeechQueue.PlaybackState(hasPosition = true, positionMs = pos, isPlaying = playing, rate = 1f, generation = generation)

    private fun spokenText(result: SpeechQueue.Result) =
        (result.decision as? SpeechQueue.Decision.Speak)?.prepared?.cleanText

    @Test
    fun futureTrackLineDoesNotBlockOnScreenCaption() {
        // 先の時刻の字幕トラック行が先に届いていても、いま表示中の画面字幕を先に読む
        queue.add(timed(30_000))
        queue.add(untimed(10_000))
        assertEquals("d10000", spokenText(queue.next(state(10_200))))
        // 字幕トラック行は開始時刻まで待機（開始までの時間に応じた待ち時間）
        val wait = queue.next(state(10_300)).decision as SpeechQueue.Decision.Wait
        assertEquals(SpeechQueue.MAX_POLL_MS, wait.millis)
        assertEquals("t30000", spokenText(queue.next(state(29_900))))
    }

    @Test
    fun earliestVideoTimeIsSpokenFirst() {
        // どちらも開始済み・未終了（19〜22秒、18〜21秒）なら、動画上の時刻が早い方から
        queue.add(timed(19_000))
        queue.add(timed(18_000))
        assertEquals("t18000", spokenText(queue.next(state(20_000))))
        assertEquals("t19000", spokenText(queue.next(state(20_000))))
    }

    @Test
    fun staleAndPreSeekTrackLinesAreDropped() {
        queue.add(timed(10_000))              // 10〜13秒 → 位置20秒では経過済み
        queue.add(timed(25_000, generation = 0)) // シーク前の世代
        val result = queue.next(state(20_000, generation = 1))
        assertEquals(2, result.dropped.size)
        assertEquals(0, queue.size)
    }

    @Test
    fun nextTimedStartIsReportedForSpeechBudget() {
        queue.add(timed(10_000))
        queue.add(timed(12_000))
        queue.add(untimed(11_000))
        assertEquals(12_000L, queue.nextTimedStartAfter(10_000L))
        assertEquals(null, queue.nextTimedStartAfter(12_000L))
    }

    @Test
    fun trackLineStartedLongAgoIsSkippedNotRushed() {
        // 10〜13秒の行を位置12.8秒で読むと大きく遅れて早口になるため破棄
        queue.add(timed(10_000))
        val result = queue.next(state(12_800))
        assertEquals(1, result.dropped.size)
        // 開始直後の行は読む
        queue.add(timed(12_000))
        assertEquals("t12000", spokenText(queue.next(state(12_800))))
    }

    @Test
    fun onScreenCaptionFromBeforeSwitchIsDropped() {
        queue.add(untimed(1_000, text = "old video line"))
        val result = queue.next(state(1_000, generation = 1))
        assertEquals(listOf("old video line"), result.dropped.map { it.first.cleanText })
        assertTrue(result.decision is SpeechQueue.Decision.Wait)
    }

    @Test
    fun laggingOnScreenCaptionsAreSkippedToCatchUp() {
        queue.add(untimed(1_000, createdAt = wall - 9_000, text = "old"))
        queue.add(untimed(5_000, createdAt = wall - 7_000, text = "older2"))
        queue.add(untimed(9_000, createdAt = wall - 500, text = "new"))
        val result = queue.next(state(9_500))
        assertEquals(listOf("old", "older2"), result.dropped.map { it.first.cleanText })
        assertEquals("new", spokenText(result))
    }

    @Test
    fun singleLaggingCaptionIsStillSpokenWithinMaxAge() {
        // 後続が無ければ、遅れていても上限(15秒)以内なら読む
        queue.add(untimed(1_000, createdAt = wall - 8_000, text = "only"))
        assertEquals("only", spokenText(queue.next(state(9_000))))
    }

    @Test
    fun nothingIsSpokenWhilePausedAndAgedCaptionsDropAfterResume() {
        queue.add(untimed(1_000, createdAt = wall, text = "line"))
        assertTrue(queue.next(state(1_000, playing = false)).decision is SpeechQueue.Decision.Wait)
        // 一時停止中は経過時間による破棄をしない
        wall += 60_000
        assertEquals(1, queue.size)
        queue.next(state(1_000, playing = false))
        assertEquals(1, queue.size)
        // 再開後、時間が経ちすぎた行は破棄
        val result = queue.next(state(1_000, playing = true))
        assertEquals(1, result.dropped.size)
    }
}

package com.univoice.browser.pipeline

import com.univoice.browser.model.UniVoiceSubtitleCue

/**
 * 翻訳済み字幕の「読み上げ待ち」リストと、次に読み上げる行の選択
 *
 * 翻訳と読み上げを分離し、読み上げ側は到着順ではなく「いま読むべき行」を選ぶ:
 * - 字幕トラック行（実タイムスタンプ付き）は、再生位置が開始時刻に達したものだけ
 * - 画面字幕行は即時対象。ただし遅れが溜まり新しい行が待っている場合は古い行を飛ばして追いつく
 * - 両方が対象なら動画上の時刻が早いものから
 *
 * スレッドセーフではないため、呼び出し側で同期すること
 */
class SpeechQueue(private val now: () -> Long = System::currentTimeMillis) {

    companion object {
        /** 字幕開始の少し前から読み上げを始め、TTSの起動遅延を吸収 */
        const val TIMED_LEAD_MS = 150L
        /** 終了時刻をこれ以上過ぎた字幕トラック行は読み上げずに破棄 */
        const val TIMED_STALE_TOLERANCE_MS = 1000L
        /**
         * 開始時刻をこれ以上過ぎた字幕トラック行は破棄（シーク直後の途中行や前の行の長引きで大きく遅れた行を、
         * 早口で詰め込んで次の行に食い込むより、飛ばして次の行を定刻で読む方が自然）
         */
        const val TIMED_MAX_LATE_START_MS = 2_500L
        /** 再生位置よりこれ以上先の字幕トラック行は（シーク直後など）破棄 */
        const val TIMED_MAX_AHEAD_MS = 180_000L
        /** 画面字幕行: 受信からこれ以上経過したら破棄（再生中のみ判定） */
        const val UNTIMED_MAX_AGE_MS = 15_000L
        /** 画面字幕行: 後続が待っている状態でこれ以上遅れた行は飛ばして追いつく */
        const val UNTIMED_CATCH_UP_LAG_MS = 6_000L
        const val MAX_POLL_MS = 250L
        const val IDLE_POLL_MS = 200L
    }

    data class PreparedCue(
        val cue: UniVoiceSubtitleCue,
        val cleanText: String,
        val translated: String,
        val isAlreadyJapanese: Boolean,
        /** 翻訳時点のシーク世代（シーク前の字幕トラック行を識別） */
        val generation: Int
    )

    /** 選択時点の再生状態 */
    data class PlaybackState(
        val hasPosition: Boolean,
        val positionMs: Long,
        val isPlaying: Boolean,
        val rate: Float,
        val generation: Int
    )

    sealed class Decision {
        data class Speak(val prepared: PreparedCue) : Decision()
        data class Wait(val millis: Long) : Decision()
    }

    data class Result(val decision: Decision, val dropped: List<Pair<PreparedCue, String>>)

    private val items = mutableListOf<PreparedCue>()

    val size: Int get() = items.size

    fun add(prepared: PreparedCue) {
        items.add(prepared)
    }

    /** 指定時刻より後に始まる、待機中の字幕トラック行の最も早い開始時刻（読み上げ時間の上限計算用） */
    fun nextTimedStartAfter(startMs: Long): Long? =
        items.filter { it.cue.isTimed && it.cue.startTimeMs > startMs }.minOfOrNull { it.cue.startTimeMs }

    fun clear(): List<PreparedCue> {
        val removed = items.toList()
        items.clear()
        return removed
    }

    fun next(state: PlaybackState): Result {
        val dropped = mutableListOf<Pair<PreparedCue, String>>()
        val t = now()

        // 1. 読み上げる意味のなくなった行を破棄
        items.removeAll { p ->
            val reason = staleReason(p, state, t)
            if (reason != null) dropped.add(p to reason)
            reason != null
        }

        // 2. 画面字幕の遅延解消: 後続が待っていれば、大きく遅れた古い行を飛ばす
        val untimed = items.filter { !it.cue.isTimed }
        if (untimed.size >= 2) {
            val newest = untimed.maxByOrNull { it.cue.createdAt }
            untimed.filter { it !== newest && t - it.cue.createdAt > UNTIMED_CATCH_UP_LAG_MS }.forEach { p ->
                items.remove(p)
                dropped.add(p to "遅延解消のため古い行をスキップ")
            }
        }

        if (!state.isPlaying || items.isEmpty()) {
            return Result(Decision.Wait(IDLE_POLL_MS), dropped)
        }

        // 3. いま読むべき行のうち、動画上の時刻が最も早いもの
        val candidate = items
            .filter { !it.cue.isTimed || (state.hasPosition && state.positionMs >= it.cue.startTimeMs - TIMED_LEAD_MS) }
            .minByOrNull { it.cue.startTimeMs }
        if (candidate != null) {
            items.remove(candidate)
            return Result(Decision.Speak(candidate), dropped)
        }

        // 4. 字幕トラック行の開始まで待機（シーク等に反応できるよう上限あり）
        val nextTimedStart = items.filter { it.cue.isTimed }.minOfOrNull { it.cue.startTimeMs }
        val waitMs = if (nextTimedStart != null && state.hasPosition) {
            ((nextTimedStart - TIMED_LEAD_MS - state.positionMs) / state.rate.coerceAtLeast(0.1f)).toLong()
                .coerceIn(20L, MAX_POLL_MS)
        } else {
            IDLE_POLL_MS
        }
        return Result(Decision.Wait(waitMs), dropped)
    }

    private fun staleReason(p: PreparedCue, state: PlaybackState, t: Long): String? {
        val cue = p.cue
        // シーク・動画切替より前に受け取った行（翻訳中に切替が起きた行を含む）は読まない
        if (p.generation != state.generation) return "シーク・動画切替前の行"
        return if (cue.isTimed) {
            when {
                !state.hasPosition -> null
                state.positionMs > cue.endTimeMs + TIMED_STALE_TOLERANCE_MS -> "再生位置を過ぎた行"
                state.isPlaying && state.positionMs - cue.startTimeMs > TIMED_MAX_LATE_START_MS -> "開始時刻から大きく遅れた行"
                state.positionMs < cue.startTimeMs - TIMED_MAX_AHEAD_MS -> "再生位置より大きく先の行"
                else -> null
            }
        } else {
            if (state.isPlaying && t - cue.createdAt > UNTIMED_MAX_AGE_MS) "受信から時間が経ちすぎた行" else null
        }
    }
}

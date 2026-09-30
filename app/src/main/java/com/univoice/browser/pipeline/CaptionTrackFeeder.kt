package com.univoice.browser.pipeline

/**
 * 字幕トラック全体を保持し、再生位置の少し先までの行を順次払い出す
 *
 * - 一括投入すると待機行が後続を塞ぐため、先読み範囲（lookahead）内の行だけを渡す
 * - シーク時は払い出し位置を巻き戻し／早送りし、同じトラックから投入を再開できる
 *
 * スレッドセーフではないため、呼び出し側で同期すること
 */
class CaptionTrackFeeder(private val lookaheadMs: Long = DEFAULT_LOOKAHEAD_MS) {

    companion object {
        const val DEFAULT_LOOKAHEAD_MS = 45_000L
        /** 終了直前の行も拾えるよう、再生位置からこの分だけ過去までは払い出し対象にする */
        private const val PAST_MARGIN_MS = 500L
    }

    data class TrackCue(val startMs: Long, val endMs: Long, val text: String)

    private var cues: List<TrackCue> = emptyList()
    private var nextIndex = 0

    /** トラックが属する動画ID（動画切替時に前動画のトラックを使わないため） */
    var videoId: String? = null
        private set

    /** 同一トラックの再取得を判別するキー */
    var trackKey: String? = null
        private set

    val hasTrack: Boolean get() = cues.isNotEmpty()

    /**
     * トラックを設定
     * @return 新しいトラックに切り替わった場合 true（同一トラックの再取得では false）
     */
    fun setTrack(videoId: String?, trackKey: String, newCues: List<TrackCue>, positionMs: Long): Boolean {
        if (trackKey == this.trackKey && videoId == this.videoId) return false
        this.videoId = videoId
        this.trackKey = trackKey
        cues = newCues.sortedBy { it.startMs }
        seekTo(positionMs)
        return true
    }

    /** 払い出し位置を、指定位置でまだ終わっていない最初の行へ移動 */
    fun seekTo(positionMs: Long) {
        val threshold = positionMs - PAST_MARGIN_MS
        val idx = cues.indexOfFirst { it.endMs >= threshold }
        nextIndex = if (idx < 0) cues.size else idx
    }

    /** 再生位置から lookahead 以内に始まる未払い出しの行を返す */
    fun takeDue(positionMs: Long): List<TrackCue> {
        if (nextIndex >= cues.size) return emptyList()
        val due = mutableListOf<TrackCue>()
        val limit = positionMs + lookaheadMs
        while (nextIndex < cues.size && cues[nextIndex].startMs <= limit) {
            val cue = cues[nextIndex++]
            if (cue.endMs >= positionMs - PAST_MARGIN_MS) due.add(cue)
        }
        return due
    }

    fun clear() {
        cues = emptyList()
        nextIndex = 0
        videoId = null
        trackKey = null
    }
}

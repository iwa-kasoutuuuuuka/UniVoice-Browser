package com.univoice.browser.pipeline

/**
 * 動画の再生位置を推定する時計
 * JS の timeupdate (約250ms間隔) で得た位置を基準に、通知の合間は経過時間×再生速度で補間する
 *
 * スレッドセーフ（すべての公開メソッドは同期化）
 */
class PlaybackClock(private val now: () -> Long = System::currentTimeMillis) {

    companion object {
        /** 期待される進行からこれ以上外れた通知はシーク（ジャンプ）とみなす */
        const val SEEK_THRESHOLD_MS = 2500L
        /**
         * 最後の通知からの補間上限（timeupdate は通常250ms間隔）
         * バッファリング中は通知が止まるため、無制限に補間すると推定位置だけが先行してしまう
         */
        const val MAX_EXTRAPOLATION_MS = 1500L
    }

    private var basePositionMs = -1L
    private var baseWallMs = 0L
    private var playing = false
    private var rate = 1.0f

    val hasPosition: Boolean @Synchronized get() = basePositionMs >= 0

    val isPlaying: Boolean @Synchronized get() = playing

    val playbackRate: Float @Synchronized get() = rate

    /**
     * 再生位置を更新
     * @return シーク（推定位置からの大きなジャンプ）を検知した場合 true
     */
    @Synchronized
    fun update(isPlaying: Boolean, positionMs: Long): Boolean {
        // 前回の「通知された位置」を基準に判定する（推定位置基準だと、バッファリングで通知が止まった後の
        // 通常の通知まで巻き戻しと誤判定する）。前進は経過時間分までを許容し、後退は閾値を超えたらシーク
        val isSeek = basePositionMs >= 0 && run {
            val expectedAdvance = if (playing) ((now() - baseWallMs) * rate).toLong() else 0L
            positionMs < basePositionMs - SEEK_THRESHOLD_MS ||
                positionMs > basePositionMs + expectedAdvance + SEEK_THRESHOLD_MS
        }
        basePositionMs = positionMs
        baseWallMs = now()
        playing = isPlaying
        return isSeek
    }

    @Synchronized
    fun setRate(newRate: Float) {
        if (newRate <= 0f || newRate.isNaN()) return
        // 速度変更前までの進行を確定してから切り替え
        if (basePositionMs >= 0) {
            basePositionMs = estimateLocked()
            baseWallMs = now()
        }
        rate = newRate
    }

    @Synchronized
    fun positionMs(): Long = estimateLocked()

    @Synchronized
    fun reset() {
        basePositionMs = -1L
        playing = false
        rate = 1.0f
    }

    private fun estimateLocked(): Long {
        if (basePositionMs < 0) return -1L
        if (!playing) return basePositionMs
        val elapsed = (now() - baseWallMs).coerceIn(0L, MAX_EXTRAPOLATION_MS)
        return basePositionMs + (elapsed * rate).toLong()
    }
}

/**
 * 字幕トラック由来（実タイムスタンプ付き）字幕の処理状況を時間範囲で記録し、
 * 画面上の字幕（DOM）から同じ行を二重に読み上げるのを防ぐ
 *
 * スレッドセーフ（すべての公開メソッドは同期化）
 */
class TimedCueRegistry(private val now: () -> Long = System::currentTimeMillis) {

    companion object {
        private const val MAX_ENTRIES = 300
        private const val MATCH_MARGIN_MS = 500L
        /** 読み上げ済みの行と同じ区間のDOM字幕を抑止する期間（シーク後の再視聴では再び読み上げる） */
        private const val SPOKEN_SUPPRESS_WINDOW_MS = 20_000L
        /** 読み上げ待ちのまま放置された行（処理経路の取りこぼし等）が画面字幕を抑止し続けないための上限 */
        private const val PENDING_TTL_MS = 240_000L
    }

    private enum class State { PENDING, SPOKEN, DROPPED }

    private class Entry(val startMs: Long, val endMs: Long, var state: State, var updatedAtWall: Long)

    private val entries = ArrayDeque<Entry>()

    @Synchronized
    fun registerPending(startMs: Long, endMs: Long) {
        entries.addLast(Entry(startMs, endMs, State.PENDING, now()))
        while (entries.size > MAX_ENTRIES) entries.removeFirst()
    }

    @Synchronized
    fun markSpoken(startMs: Long, endMs: Long) = mark(startMs, endMs, State.SPOKEN)

    @Synchronized
    fun markDropped(startMs: Long, endMs: Long) = mark(startMs, endMs, State.DROPPED)

    /** シーク時: 未処理の行をすべて破棄扱いにし、DOM字幕での読み上げに委ねる */
    @Synchronized
    fun dropAllPending() {
        val t = now()
        entries.filter { it.state == State.PENDING }.forEach { it.state = State.DROPPED; it.updatedAtWall = t }
    }

    /** 指定位置が、字幕トラック側で処理予定または直近に読み上げ済みの区間に含まれるか */
    @Synchronized
    fun isCoveredByTrack(positionMs: Long): Boolean {
        val t = now()
        return entries.any { e ->
            positionMs >= e.startMs - MATCH_MARGIN_MS && positionMs <= e.endMs + MATCH_MARGIN_MS &&
                ((e.state == State.PENDING && t - e.updatedAtWall < PENDING_TTL_MS) ||
                    (e.state == State.SPOKEN && t - e.updatedAtWall < SPOKEN_SUPPRESS_WINDOW_MS))
        }
    }

    @Synchronized
    fun clear() = entries.clear()

    /**
     * 指定範囲に完全に含まれる読み上げ待ちの行を更新（結合済み字幕は構成行すべてを含む）
     * 部分的に重なるだけの隣接行（自動生成字幕は時間帯が重なりやすい）は対象外
     */
    private fun mark(startMs: Long, endMs: Long, state: State) {
        val t = now()
        entries.filter { it.state == State.PENDING && it.startMs >= startMs && it.endMs <= endMs }
            .forEach { it.state = state; it.updatedAtWall = t }
    }
}

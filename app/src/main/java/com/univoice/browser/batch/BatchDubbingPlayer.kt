package com.univoice.browser.batch

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 事前生成された日本語吹き替え音声（バッチキャッシュ）と
 * 動画再生位置（currentTimeMs）を同期再生する専用プレイヤー
 */
class BatchDubbingPlayer(private val context: Context) {

    companion object {
        private const val TAG = "BatchDubbingPlayer"
    }

    private var segments: List<TimedSegment> = emptyList()
    private var currentPlayingIndex: Int = -1
    private var mediaPlayer: MediaPlayer? = null
    private var isDubbingActive = false

    var onSegmentChanged: ((TimedSegment) -> Unit)? = null
    var onPlaybackStateChanged: ((Boolean) -> Unit)? = null

    val isActive: Boolean
        get() = isDubbingActive

    /**
     * バッチ生成完了後のセグメント一覧をロード
     */
    fun loadSegments(newSegments: List<TimedSegment>) {
        segments = newSegments.sortedBy { it.startMs }
        currentPlayingIndex = -1
        Log.i(TAG, "[UniVoiceBrowser] バッチプレイヤーに ${segments.size} 件の吹き替えセグメントをロードしました")
    }

    /**
     * 日本語版再生モードの開始
     */
    fun startDubbing(initialPositionMs: Long = 0L) {
        isDubbingActive = true
        currentPlayingIndex = -1
        onPlaybackStateChanged?.invoke(true)
        Log.i(TAG, "[UniVoiceBrowser] 日本語版吹き替え同期再生を開始しました (開始位置: ${initialPositionMs}ms)")

        // 開始位置に対応するセグメントが存在すれば即座に再生
        val targetSegment = segments.find { seg ->
            initialPositionMs >= seg.startMs && initialPositionMs <= seg.endMs
        } ?: segments.firstOrNull()

        if (targetSegment != null) {
            currentPlayingIndex = targetSegment.index
            val offset = (initialPositionMs - targetSegment.startMs).coerceAtLeast(0L)
            playSegmentAudio(targetSegment, offset)
            onSegmentChanged?.invoke(targetSegment)
        }
    }

    /**
     * 日本語版再生モードの一時停止
     */
    fun pauseDubbing() {
        try {
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.pause()
            }
        } catch (e: Exception) {
            Log.w(TAG, "[UniVoiceBrowser] 音声一時停止警告: ${e.message}")
        }
        onPlaybackStateChanged?.invoke(false)
    }

    /**
     * 日本語版再生モードの停止
     */
    fun stopDubbing() {
        isDubbingActive = false
        currentPlayingIndex = -1
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (e: Exception) {
            Log.w(TAG, "[UniVoiceBrowser] 音声停止警告: ${e.message}")
        } finally {
            mediaPlayer = null
        }
        onPlaybackStateChanged?.invoke(false)
        Log.i(TAG, "[UniVoiceBrowser] 日本語版吹き替え同期再生を停止しました")
    }

    /**
     * 動画の再生状態および再生時間（currentTimeMs）の進行に合わせて呼び出される同期処理
     */
    fun onVideoPositionChanged(isPlaying: Boolean, currentTimeMs: Long) {
        if (!isDubbingActive || segments.isEmpty()) return

        if (!isPlaying) {
            // 動画が停止されたら吹き替え音声も一時停止
            pauseDubbing()
            return
        }

        // 現在の動画再生位置に合致するセグメントを検索
        val matchingSegment = segments.find { seg ->
            currentTimeMs >= seg.startMs - 300L && currentTimeMs <= seg.endMs + 600L
        }

        if (matchingSegment != null) {
            if (matchingSegment.index != currentPlayingIndex) {
                currentPlayingIndex = matchingSegment.index
                val offset = (currentTimeMs - matchingSegment.startMs).coerceAtLeast(0L)
                playSegmentAudio(matchingSegment, offset)
                onSegmentChanged?.invoke(matchingSegment)
            } else {
                // 同じセグメント内で動画が再開された場合
                if (mediaPlayer != null && !mediaPlayer!!.isPlaying) {
                    try {
                        mediaPlayer?.start()
                    } catch (_: Exception) {}
                }
            }
        } else {
            // セグメント間の無音区間
            if (currentPlayingIndex != -1) {
                val lastSeg = segments.getOrNull(currentPlayingIndex)
                if (lastSeg != null && currentTimeMs > lastSeg.endMs + 1000L) {
                    currentPlayingIndex = -1
                    stopCurrentMediaPlayer()
                }
            }
        }
    }

    private fun playSegmentAudio(segment: TimedSegment, offsetMs: Long) {
        val audioFile = segment.generatedAudioFile
        if (audioFile == null || !audioFile.exists() || audioFile.length() == 0L) {
            Log.w(TAG, "[UniVoiceBrowser] セグメント #${segment.index} の音声ファイルが存在しません: ${audioFile?.name}")
            return
        }

        stopCurrentMediaPlayer()

        try {
            mediaPlayer = MediaPlayer().apply {
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                setAudioAttributes(audioAttributes)
                setVolume(1.0f, 1.0f)
                setDataSource(audioFile.absolutePath)
                setOnPreparedListener { mp ->
                    if (offsetMs in 1 until mp.duration) {
                        mp.seekTo(offsetMs.toInt())
                    }
                    mp.start()
                    Log.d(TAG, "[UniVoiceBrowser] 日本語音声再生開始: #${segment.index} (${audioFile.name})")
                }
                setOnCompletionListener {
                    Log.d(TAG, "[UniVoiceBrowser] セグメント #${segment.index} 音声再生終了")
                }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "[UniVoiceBrowser] MediaPlayer エラー: what=$what, extra=$extra")
                    true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.e(TAG, "[UniVoiceBrowser] セグメント #${segment.index} 再生開始例外: ${e.message}", e)
        }
    }

    private fun stopCurrentMediaPlayer() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null
    }

    fun release() {
        stopDubbing()
    }
}

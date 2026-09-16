package com.univoice.browser.batch

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlin.math.abs

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
    private var isPreparing = false
    private var lastVideoPositionMs: Long = -1L

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
        lastVideoPositionMs = -1L
        Log.i(TAG, "[UniVoiceBrowser] バッチプレイヤーに ${segments.size} 件の吹き替えセグメントをロードしました")
    }

    /**
     * キャッシュディレクトリからセグメント一覧と音声ファイルを復元してロード
     */
    fun loadFromCacheDirectory(cacheDir: File): Boolean {
        if (!cacheDir.exists() || !cacheDir.isDirectory) return false
        val audioFiles = cacheDir.listFiles { _, name -> name.startsWith("dubbing_") && (name.endsWith(".mp3") || name.endsWith(".wav")) }
            ?.sortedBy { file ->
                file.nameWithoutExtension.substringAfter("dubbing_").toIntOrNull() ?: 0
            } ?: emptyList()

        if (audioFiles.isEmpty()) return false

        val loadedSegments = mutableListOf<TimedSegment>()
        
        val segmentsJsonFile = File(cacheDir, "segments.json")
        if (segmentsJsonFile.exists()) {
            try {
                val type = object : TypeToken<List<TimedSegment>>() {}.type
                val parsedSegments: List<TimedSegment> = Gson().fromJson(segmentsJsonFile.readText(), type)
                
                parsedSegments.forEachIndexed { idx, seg ->
                    val actualIndex = if (seg.index > 0) seg.index else idx
                    val matchingFile = audioFiles.find { it.nameWithoutExtension.substringAfter("dubbing_").toIntOrNull() == actualIndex }
                        ?: audioFiles.getOrNull(actualIndex)
                    loadedSegments.add(
                        TimedSegment(
                            index = actualIndex,
                            startMs = seg.startMs,
                            endMs = seg.endMs,
                            originalText = seg.originalText.ifBlank { "字幕 #${actualIndex + 1}" },
                            translatedText = seg.translatedText,
                            generatedAudioFile = matchingFile ?: seg.generatedAudioFile
                        )
                    )
                }
                loadSegments(loadedSegments)
                return true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse segments.json", e)
            }
        }

        var startMs = 0L
        audioFiles.forEachIndexed { idx, file ->
            val durationMs = 4000L
            val seg = TimedSegment(
                index = idx,
                startMs = startMs,
                endMs = startMs + durationMs,
                originalText = "字幕セグメント #${idx + 1}",
                translatedText = "日本語吹き替え音声 #${idx + 1}",
                generatedAudioFile = file
            )
            loadedSegments.add(seg)
            startMs += durationMs
        }
        loadSegments(loadedSegments)
        return true
    }

    /**
     * 日本語版再生モードの開始
     */
    fun startDubbing(initialPositionMs: Long = 0L) {
        isDubbingActive = true
        currentPlayingIndex = -1
        lastVideoPositionMs = initialPositionMs
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
        lastVideoPositionMs = -1L
        stopCurrentMediaPlayer()
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

        // シーク検知 (前回の再生位置から急激にジャンプした場合、または巻き戻った場合)
        val isSeekDetected = lastVideoPositionMs != -1L &&
                (currentTimeMs < lastVideoPositionMs - 500L || currentTimeMs > lastVideoPositionMs + 2500L)
        lastVideoPositionMs = currentTimeMs

        if (isSeekDetected) {
            Log.d(TAG, "[UniVoiceBrowser] シーク操作検知: position=${currentTimeMs}ms (直前音声をリセット)")
            currentPlayingIndex = -1
            stopCurrentMediaPlayer()
        }

        // 現在の動画再生位置に合致するセグメントを検索
        val matchingSegment = segments.filter { seg ->
            currentTimeMs >= seg.startMs - 300L && currentTimeMs <= seg.endMs + 600L
        }.minByOrNull { abs(currentTimeMs - it.startMs) }

        if (matchingSegment != null) {
            if (matchingSegment.index != currentPlayingIndex) {
                currentPlayingIndex = matchingSegment.index
                val offset = (currentTimeMs - matchingSegment.startMs).coerceAtLeast(0L)
                playSegmentAudio(matchingSegment, offset)
                onSegmentChanged?.invoke(matchingSegment)
            } else {
                // 同じセグメント内で動画が再開された場合
                if (mediaPlayer != null && !mediaPlayer!!.isPlaying && !isPreparing) {
                    try {
                        mediaPlayer?.start()
                    } catch (_: Exception) {}
                }
            }
        } else {
            // セグメント間の無音区間
            if (currentPlayingIndex != -1) {
                val lastSeg = segments.find { it.index == currentPlayingIndex } ?: segments.getOrNull(currentPlayingIndex)
                if (lastSeg != null) {
                    if (currentTimeMs < lastSeg.startMs - 500L || currentTimeMs > lastSeg.endMs + 3000L) {
                        // セグメント時間から大幅に離脱した場合は停止
                        currentPlayingIndex = -1
                        stopCurrentMediaPlayer()
                    } else if (mediaPlayer?.isPlaying != true) {
                        // 話し終わっていればリセット
                        currentPlayingIndex = -1
                        stopCurrentMediaPlayer()
                    }
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
                    isPreparing = false
                    if (offsetMs in 1 until mp.duration) {
                        mp.seekTo(offsetMs.toInt())
                    }
                    mp.start()
                    Log.d(TAG, "[UniVoiceBrowser] 日本語音声再生開始: #${segment.index} (${audioFile.name})")
                }
                setOnCompletionListener {
                    Log.d(TAG, "[UniVoiceBrowser] セグメント #${segment.index} 音声再生終了")
                    stopCurrentMediaPlayer()
                }
                setOnErrorListener { mp, what, extra ->
                    Log.e(TAG, "[UniVoiceBrowser] MediaPlayer エラー: what=$what, extra=$extra")
                    isPreparing = false
                    try {
                        mp.release()
                    } catch (_: Exception) {}
                    mediaPlayer = null
                    true
                }
                isPreparing = true
                prepareAsync()
            }
        } catch (e: Exception) {
            isPreparing = false
            Log.e(TAG, "[UniVoiceBrowser] セグメント #${segment.index} 再生開始例外: ${e.message}", e)
        }
    }

    private fun stopCurrentMediaPlayer() {
        try {
            mediaPlayer?.stop()
        } catch (_: Exception) {}
        try {
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null
    }

    fun release() {
        stopDubbing()
    }
}

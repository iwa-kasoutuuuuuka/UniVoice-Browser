package com.univoice.browser.batch

import java.io.File

/**
 * タイムスタンプと許容秒数を保持するセグメント
 */
data class TimedSegment(
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val originalText: String,
    var translatedText: String = "",
    var generatedAudioFile: File? = null
) {
    /** 発話許容秒数（Duration） */
    val durationSec: Float
        get() = ((endMs - startMs).coerceAtLeast(500L)) / 1000.0f

    /** 日本語の自然な発話における推奨最大文字数 (1秒あたり約6〜7文字) */
    val maxRecommendedJapaneseChars: Int
        get() = (durationSec * 6.5f).toInt().coerceAtLeast(3)
}

/**
 * バッチ翻訳処理の全体ジョブ状態（総合進捗＋音声・翻訳・映像の3要素個別進捗）
 */
data class BatchJobStatus(
    val videoId: String,
    val title: String,
    val progressPercent: Int = 0,
    val audioProgressPercent: Int = 0,
    val transProgressPercent: Int = 0,
    val videoProgressPercent: Int = 0,
    val statusMessageJapanese: String = "待機中",
    val isCompleted: Boolean = false,
    val errorMessage: String? = null
)

/**
 * ダウンロード動画一覧リスト用データモデル
 */
data class DownloadedVideoItem(
    val videoId: String,
    val title: String,
    val videoUrl: String,
    val timestamp: Long = System.currentTimeMillis(),
    val totalSegments: Int = 0,
    val isFullyCompleted: Boolean = false,
    val audioProgress: Int = 0,
    val transProgress: Int = 0,
    val videoProgress: Int = 0,
    val statusMessage: String = "準備中"
)

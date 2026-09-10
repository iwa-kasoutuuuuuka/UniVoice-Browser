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
 * バッチ翻訳処理の全体ジョブ状態
 */
data class BatchJobStatus(
    val videoId: String,
    val title: String,
    val progressPercent: Int = 0,
    val statusMessageJapanese: String = "待機中",
    val isCompleted: Boolean = false,
    val errorMessage: String? = null
)

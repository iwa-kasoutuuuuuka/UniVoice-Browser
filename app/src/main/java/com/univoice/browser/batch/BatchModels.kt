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

    /** 標準速度で尺内に収まるモーラ数 */
    val maxMorae: Float
        get() = com.univoice.browser.pipeline.JapaneseSpeechTiming.maxMoraeFor(durationSec)

    /** 翻訳プロンプトで指示する最大文字数の目安（モーラ数から漢字かな交じり文の平均で換算） */
    val maxRecommendedJapaneseChars: Int
        get() = com.univoice.browser.pipeline.JapaneseSpeechTiming.maxCharsFor(durationSec)
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

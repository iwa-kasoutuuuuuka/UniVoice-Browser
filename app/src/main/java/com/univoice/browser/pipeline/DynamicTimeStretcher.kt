package com.univoice.browser.pipeline

import android.util.Log

/**
 * 動画字幕の表示時間と日本語テキストの文字数・モーラ数から
 * 最適なTTS発話速度をリアルタイムに自動算出し、リップシンク（タイミング同期）を行うモジュール
 */
object DynamicTimeStretcher {

    private const val TAG = "DynamicTimeStretcher"

    // 日本語の標準的な発話速度: 1秒あたり約7モーラ (文字)
    private const val MORAE_PER_SECOND_STANDARD = 7.0f

    // 安全な動的速度調整の最小値と最大値
    private const val MIN_SPEED = 0.85f
    private const val MAX_SPEED = 1.45f

    /**
     * 最適な発話速度倍率を算出
     * @param translatedJapanese 翻訳後の日本語テキスト
     * @param availableDurationMs 字幕の表示可能時間（次の字幕開始までのミリ秒、または現在の字幕持続時間）
     * @param baseSpeed ユーザーが設定画面で指定した基準発話速度
     * @return 最適化された発話速度倍率
     */
    fun calculateOptimalSpeed(
        translatedJapanese: String,
        availableDurationMs: Long,
        baseSpeed: Float = 1.0f
    ): Float {
        if (translatedJapanese.isBlank() || availableDurationMs <= 0) {
            return baseSpeed
        }

        // 記号や空白を除外した純粋な文字数を概算モーラ数として推定
        val cleanLength = translatedJapanese.replace(Regex("[\\s\\p{Punct}、。！？・〜]"), "").length
        if (cleanLength == 0) return baseSpeed

        // 基準速度で発話した場合の所要秒数
        val estimatedDurationSec = (cleanLength / (MORAE_PER_SECOND_STANDARD * baseSpeed)).coerceAtLeast(0.6f)
        val availableDurationSec = availableDurationMs / 1000.0f

        // 時間がタイトな場合は速度を上げ、余裕がある場合は自然な速度に保つ
        val speedRatio = estimatedDurationSec / availableDurationSec
        val targetSpeed = (baseSpeed * speedRatio).coerceIn(MIN_SPEED, MAX_SPEED)

        Log.d(
            TAG,
            "[UniVoiceBrowser] リップシンク速度計算: 文字数=$cleanLength, 許容時間=${availableDurationSec}s, 推定発話時間=${estimatedDurationSec}s -> 適用速度=${String.format("%.2f", targetSpeed)}倍"
        )

        return targetSpeed
    }
}

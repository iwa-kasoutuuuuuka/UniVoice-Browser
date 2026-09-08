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

    // 許容される速度調整の最小値と最大値
    private const val MIN_SPEED = 0.5f
    private const val MAX_SPEED = 2.5f

    /**
     * 最適な発話速度倍率を算出
     * ユーザーが設定画面で指定した基準速度 (baseSpeed) を最優先とし、
     * 字幕の残り表示時間が逼迫している場合のみ、動画の進行に追従するため加速補正を行う
     *
     * @param translatedJapanese 翻訳後の日本語テキスト
     * @param availableDurationMs 字幕の表示可能時間（次の字幕開始までのミリ秒、または現在の字幕持続時間）
     * @param baseSpeed ユーザーが設定画面で指定した基準発話速度 (0.5〜2.5倍)
     * @return 最適化された発話速度倍率
     */
    fun calculateOptimalSpeed(
        translatedJapanese: String,
        availableDurationMs: Long,
        baseSpeed: Float = 1.0f
    ): Float {
        val safeBaseSpeed = baseSpeed.coerceIn(MIN_SPEED, MAX_SPEED)
        if (translatedJapanese.isBlank() || availableDurationMs <= 0) {
            return safeBaseSpeed
        }

        // 記号や空白を除外した純粋な文字数を概算モーラ数として推定
        val cleanLength = translatedJapanese.replace(Regex("[\\s\\p{Punct}、。！？・〜]"), "").length
        if (cleanLength == 0) return safeBaseSpeed

        // ユーザー指定の基準速度で発話した場合の推定所要秒数
        val estimatedDurationSec = cleanLength / (MORAE_PER_SECOND_STANDARD * safeBaseSpeed)
        val availableDurationSec = (availableDurationMs / 1000.0f).coerceAtLeast(0.5f)

        // 字幕の表示時間内に発話が収まらない場合のみ、次の字幕に追従できるよう加速補正
        // 余裕がある場合はユーザー指定の基準速度（safeBaseSpeed）をそのまま100%維持
        val speedNeeded = if (estimatedDurationSec > availableDurationSec) {
            cleanLength / (MORAE_PER_SECOND_STANDARD * availableDurationSec)
        } else {
            safeBaseSpeed
        }

        // ユーザーが設定した発話速度を下回らないよう保証（ユーザー設定を最優先）
        val targetSpeed = maxOf(safeBaseSpeed, speedNeeded).coerceIn(MIN_SPEED, MAX_SPEED)

        Log.d(
            TAG,
            "[UniVoiceBrowser] 発話速度計算: 設定基準=${safeBaseSpeed}倍, 文字数=$cleanLength, 許容時間=${availableDurationSec}s, 所要時間=${String.format("%.2f", estimatedDurationSec)}s -> 適用速度=${String.format("%.2f", targetSpeed)}倍"
        )

        return targetSpeed
    }
}

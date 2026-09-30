package com.univoice.browser.pipeline

import android.util.Log

/**
 * 動画字幕の表示時間と日本語テキストの文字数・モーラ数から
 * 最適なTTS発話速度をリアルタイムに自動算出し、リップシンク（タイミング同期）を行うモジュール
 */
object DynamicTimeStretcher {

    private const val TAG = "DynamicTimeStretcher"

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
     * @param estimatedRawDurationMs 1.0倍速での推定音声長（使用中エンジンの実測で補正した値）。
     *        null の場合は既定モデル（余白 + モーラ数/話速）で推定
     * @return 最適化された発話速度倍率
     */
    fun calculateOptimalSpeed(
        translatedJapanese: String,
        availableDurationMs: Long,
        baseSpeed: Float = 1.0f,
        estimatedRawDurationMs: Long? = null
    ): Float {
        val safeBaseSpeed = baseSpeed.coerceIn(MIN_SPEED, MAX_SPEED)
        if (translatedJapanese.isBlank() || availableDurationMs <= 0) return safeBaseSpeed
        if (JapaneseSpeechTiming.estimateMorae(translatedJapanese) <= 0f) return safeBaseSpeed

        val rawMs = estimatedRawDurationMs ?: (JapaneseSpeechTiming.estimateDurationSec(translatedJapanese) * 1000).toLong()
        val targetSpeed = speedFor(rawMs, availableDurationMs, safeBaseSpeed)

        Log.d(
            TAG,
            "[UniVoiceBrowser] 発話速度計算: 設定基準=${safeBaseSpeed}倍, 推定音声長=${rawMs}ms(1.0倍), 許容時間=${availableDurationMs}ms -> 適用速度=${String.format("%.2f", targetSpeed)}倍"
        )
        return targetSpeed
    }

    /**
     * 1.0倍速の音声長 rawMs を availableMs 内に収める速度（基準速度を下回らない）
     * 再生速度を上げると余白も含めた音声全体が 1/速度 に縮む
     */
    internal fun speedFor(rawMs: Long, availableMs: Long, baseSpeed: Float): Float {
        val availableSec = (availableMs / 1000f).coerceAtLeast(0.5f)
        val needed = (rawMs / 1000f) / availableSec
        return maxOf(baseSpeed, needed).coerceIn(MIN_SPEED, MAX_SPEED)
    }

    /**
     * 生成されたPCM音声波形（またはミリ秒長）とタイムスロット（開始時刻〜終了時刻）の
     * ズレから波形タイムストレッチ率（再生速度比率）を厳密計算
     */
    fun calculateWaveformStretchRatio(
        actualAudioDurationMs: Long,
        targetSlotDurationMs: Long
    ): Float {
        if (actualAudioDurationMs <= 0 || targetSlotDurationMs <= 0) return 1.0f
        val ratio = actualAudioDurationMs.toFloat() / targetSlotDurationMs.toFloat()
        return ratio.coerceIn(0.7f, 1.6f)
    }
}

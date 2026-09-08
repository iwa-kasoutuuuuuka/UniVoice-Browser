package com.univoice.browser.tts

import com.univoice.browser.model.TtsEngineType

/**
 * 音声合成 (TTS) エンジンの共通インターフェース
 */
interface TtsEngine {

    val engineType: TtsEngineType

    /**
     * 音声合成エンジンの初期化
     */
    suspend fun initialize(): Boolean

    /**
     * テキストを音声合成して即時再生
     * @param text 日本語読み上げテキスト
     * @param speed 発話速度倍率 (0.5〜2.0)
     * @param pitch 音声ピッチ (0.5〜2.0)
     * @return 成功したかどうかのResult
     */
    suspend fun synthesizeAndPlay(text: String, speed: Float = 1.0f, pitch: Float = 1.0f): Result<Unit>

    /**
     * 現在再生中の音声を即座に停止
     */
    fun stop()

    /**
     * リソース解放
     */
    fun release()
}

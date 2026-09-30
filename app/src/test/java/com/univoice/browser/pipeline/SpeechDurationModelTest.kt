package com.univoice.browser.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechDurationModelTest {

    @Test
    fun priorMatchesMeasuredGoogleTtsDefaults() {
        val model = SpeechDurationModel()
        // 余白350ms + 16拍 × 169ms ≒ 3.06秒（実測: 「僕らは互いに 気付いている」16拍 → 2736ms 〜 回帰残差の範囲）
        assertEquals(350.0, model.overheadMs, 1.0)
        assertEquals(1000.0 / 5.9, model.msPerMora, 1.0)
        assertEquals(3062.0, model.estimateRawMs(16f).toDouble(), 2.0)
    }

    @Test
    fun learnsSlowerVoiceFromMeasurements() {
        val model = SpeechDurationModel()
        // 別エンジン: 余白600ms、220ms/拍
        repeat(30) { i ->
            val morae = 5f + (i % 6) * 3f
            model.observe(morae, (600 + 220 * morae).toLong())
        }
        assertEquals(220.0, model.msPerMora, 8.0)
        assertEquals(600.0, model.overheadMs, 60.0)
        assertEquals(30, model.sampleCount)
    }

    @Test
    fun learnsFromSimilarLengthUtterancesWithoutBlowingUp() {
        val model = SpeechDurationModel()
        // ほぼ同じ長さの発話ばかりでも（傾きが決まりにくい）、推定値はその長さで実測に近づく
        repeat(20) { model.observe(12f, 2600L) }
        assertEquals(2600.0, model.estimateRawMs(12f).toDouble(), 150.0)
        assertTrue(model.msPerMora in 80.0..300.0)
    }

    @Test
    fun rejectsOutliers() {
        val model = SpeechDurationModel()
        assertFalse(model.observe(10f, 20_000L)) // 途中停止や別音声の取り違え
        assertFalse(model.observe(10f, 100L))
        assertFalse(model.observe(0f, 1000L))
        assertEquals(0, model.sampleCount)
    }

    @Test
    fun speedForKeepsBaseSpeedWhenItFitsAndAcceleratesOtherwise() {
        // 1.0倍で2.5秒の音声が3秒に収まる → 基準速度のまま
        assertEquals(1.0f, DynamicTimeStretcher.speedFor(2500L, 3000L, 1.0f), 0.001f)
        // 3秒の音声を2秒に収める → 1.5倍
        assertEquals(1.5f, DynamicTimeStretcher.speedFor(3000L, 2000L, 1.0f), 0.001f)
        // 上限 2.5倍
        assertEquals(2.5f, DynamicTimeStretcher.speedFor(9000L, 1000L, 1.0f), 0.001f)
        // ユーザー基準速度を下回らない
        assertEquals(1.3f, DynamicTimeStretcher.speedFor(1000L, 3000L, 1.3f), 0.001f)
    }
}

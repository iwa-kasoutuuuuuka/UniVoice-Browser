package com.univoice.browser.pipeline

import java.util.concurrent.ConcurrentHashMap

/**
 * TTS 発話の音声長（1.0倍速換算）を「固定の余白 + モーラ数 × 1モーラあたりの時間」で推定し、
 * 実測値で逐次補正するモデル（指数重み付き最小二乗）
 *
 * 初期値は Google TTS の実測（17発話の回帰: 169ms/モーラ、余白 344ms）に基づく。
 * エンジン・声によって話速や前後の無音が異なるため、エンジンごとに実測で学習する。
 */
class SpeechDurationModel(
    priorOverheadMs: Double = JapaneseSpeechTiming.UTTERANCE_OVERHEAD_MS.toDouble(),
    priorMsPerMora: Double = 1000.0 / JapaneseSpeechTiming.MORAE_PER_SECOND,
    private val decay: Double = 0.93,
    priorWeight: Double = 3.0
) {
    companion object {
        private const val MIN_MS_PER_MORA = 80.0
        private const val MAX_MS_PER_MORA = 300.0
        private const val MAX_OVERHEAD_MS = 1500.0
    }

    // 重み付き和（Σw, Σwx, Σwy, Σwx², Σwxy）
    private var sw = 0.0
    private var sx = 0.0
    private var sy = 0.0
    private var sxx = 0.0
    private var sxy = 0.0

    var sampleCount = 0
        private set

    init {
        // 事前分布を短文・長文の2点の擬似観測として与え、実測が少ない間の推定を安定させる
        for (x in doubleArrayOf(4.0, 20.0)) {
            add(x, priorOverheadMs + priorMsPerMora * x, priorWeight / 2)
        }
    }

    @Synchronized
    fun observe(morae: Float, rawDurationMs: Long): Boolean {
        if (morae < 1f || rawDurationMs <= 0L) return false
        // 異常値（途中停止・無音ファイル・フォールバック音声の取り違え等）は学習しない
        val expected = estimateRawMs(morae)
        if (rawDurationMs > expected * 3 + 1000 || rawDurationMs < expected / 3) return false
        sw *= decay; sx *= decay; sy *= decay; sxx *= decay; sxy *= decay
        add(morae.toDouble(), rawDurationMs.toDouble(), 1.0)
        sampleCount++
        return true
    }

    @get:Synchronized
    val msPerMora: Double
        get() {
            val denominator = sw * sxx - sx * sx
            val slope = if (denominator > 1e-6) (sw * sxy - sx * sy) / denominator else sy / sx.coerceAtLeast(1e-6)
            return slope.coerceIn(MIN_MS_PER_MORA, MAX_MS_PER_MORA)
        }

    @get:Synchronized
    val overheadMs: Double
        get() = ((sy - msPerMora * sx) / sw).coerceIn(0.0, MAX_OVERHEAD_MS)

    /** 1.0倍速での推定音声長 */
    @Synchronized
    fun estimateRawMs(morae: Float): Long = (overheadMs + msPerMora * morae).toLong()

    private fun add(x: Double, y: Double, w: Double) {
        sw += w; sx += w * x; sy += w * y; sxx += w * x * x; sxy += w * x * y
    }
}

/**
 * TTS エンジンごとの音声長モデル（プロセス全体で共有）
 * 実際に音声を再生したエンジンが実測値を報告し、パイプラインは使用中エンジンのモデルで発話速度を決める
 */
object SpeechDurationCalibration {
    private val models = ConcurrentHashMap<String, SpeechDurationModel>()

    fun modelFor(engineKey: String): SpeechDurationModel = models.getOrPut(engineKey) { SpeechDurationModel() }

    /** 1.0倍速換算の実測音声長を報告 */
    fun report(engineKey: String, text: String, rawDurationMs: Long): Boolean =
        modelFor(engineKey).observe(JapaneseSpeechTiming.estimateMorae(text), rawDurationMs)

    fun estimateRawMs(engineKey: String, text: String): Long =
        modelFor(engineKey).estimateRawMs(JapaneseSpeechTiming.estimateMorae(text))
}

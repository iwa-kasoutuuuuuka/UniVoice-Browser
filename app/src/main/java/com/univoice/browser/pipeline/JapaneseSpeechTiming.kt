package com.univoice.browser.pipeline

/**
 * 日本語読み上げ時間の見積もり（モーラ数ベース）
 *
 * 読み上げ時間は文字数ではなくモーラ（拍）数でほぼ決まるため、リアルタイム経路の発話速度計算と
 * バッチ翻訳の尺制約・長さ検証で同じ基準を共有する
 */
object JapaneseSpeechTiming {

    /**
     * 標準速度 (1.0倍) の日本語TTSの発話速度
     * Google TTS の実測回帰（17発話）: 169ms/モーラ ≒ 5.9モーラ/秒
     * ※ 旧値 7.0 と余白なしの見積もりは、実測より平均36%（約0.8秒）短かった
     */
    const val MORAE_PER_SECOND = 5.9f

    /** 発話ごとに付く固定の余白（前後の無音・立ち上がり）。同実測で約344ms */
    const val UTTERANCE_OVERHEAD_MS = 350L

    /** 漢字1字あたりの平均モーラ数（音読み2拍・訓読み1〜3拍の平均的な値） */
    private const val MORAE_PER_KANJI = 2.0f

    /** 漢字かな交じり文の1字あたり平均モーラ数（翻訳プロンプトで文字数上限へ換算する際に使用） */
    const val AVERAGE_MORAE_PER_CHAR = 1.3f

    private const val SMALL_KANA = "ぁぃぅぇぉゃゅょゎァィゥェォャュョヮ"

    /** テキストのモーラ数を推定（句読点・記号・空白は0拍） */
    fun estimateMorae(text: String): Float {
        var morae = 0f
        var latinRun = 0
        fun flushLatin() {
            // 英字は外来語読みで概ね 1.5字 = 1拍 程度
            if (latinRun > 0) morae += maxOf(1f, latinRun / 1.5f)
            latinRun = 0
        }
        for (c in text) {
            val code = c.code
            if (c.isLetter() && code < 0x250) { latinRun++; continue }
            flushLatin()
            morae += when {
                SMALL_KANA.indexOf(c) >= 0 -> 0f           // 拗音は直前のかなと合わせて1拍
                code in 0x3041..0x309F || code in 0x30A1..0x30FA -> 1f
                c == 'ー' -> 1f
                code in 0x4E00..0x9FFF || code in 0x3400..0x4DBF || c == '々' -> MORAE_PER_KANJI
                c.isDigit() -> 1.5f
                else -> 0f
            }
        }
        flushLatin()
        return morae
    }

    /** 既定モデル（余白 + モーラ数/話速）での再生時間。speed 倍速で再生すると全体が 1/speed に縮む */
    fun estimateDurationSec(text: String, speed: Float = 1.0f): Float =
        (UTTERANCE_OVERHEAD_MS / 1000f + estimateMorae(text) / MORAE_PER_SECOND) / speed.coerceAtLeast(0.1f)

    /** 指定秒数に標準速度で収まるモーラ数（発話ごとの余白を差し引く） */
    fun maxMoraeFor(durationSec: Float): Float =
        ((durationSec - UTTERANCE_OVERHEAD_MS / 1000f) * MORAE_PER_SECOND).coerceAtLeast(0f)

    /** 指定秒数に収まる目安の文字数（翻訳プロンプト用） */
    fun maxCharsFor(durationSec: Float): Int =
        (maxMoraeFor(durationSec) / AVERAGE_MORAE_PER_CHAR).toInt().coerceAtLeast(3)
}

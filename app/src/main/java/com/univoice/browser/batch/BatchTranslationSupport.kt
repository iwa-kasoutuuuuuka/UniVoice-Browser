package com.univoice.browser.batch

import com.univoice.browser.pipeline.JapaneseSpeechTiming
import com.univoice.browser.pipeline.JapaneseText
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * バッチ一括翻訳のプロンプト構築・応答解析・品質検証（Android非依存の純粋ロジック）
 *
 * 2段階翻訳:
 *  1. 全字幕から「動画概要・文体・用語集」(VideoBrief) を作成
 *  2. VideoBrief と直前チャンクの対訳を添えて各チャンクを翻訳し、欠落・長すぎる行のみ再依頼
 */
internal object BatchTranslationSupport {

    /** 1回の概要作成リクエストに含める原文の最大文字数 */
    const val MAX_BRIEF_SOURCE_CHARS = 60_000
    const val PREVIOUS_CONTEXT_LINES = 5
    const val LOOKAHEAD_LINES = 2
    /** 推奨最大文字数に対する許容超過率（これを超えたら短縮を再依頼） */
    const val OVERLONG_TOLERANCE = 1.2f

    data class VideoBrief(
        val summary: String,
        val style: String,
        val glossary: List<Pair<String, String>>
    )

    data class Problem(val segment: TimedSegment, val reason: String)

    const val TRANSLATION_SYSTEM_INSTRUCTION = """あなたは動画吹き替え専門のプロ翻訳者です。英語字幕の各セグメントを、日本語吹き替え音声(TTS)で読み上げる自然な話し言葉に翻訳します。
【ルール】
1. 各セグメントの訳は「最大文字数」（句読点・空白を除く）以内に収めてください。収まらない場合は意味の核を残して要約・意訳してください。
2. 「用語集」の訳語と「文体」の指定を厳守し、動画全体で一貫させてください。
3. 1つの文が複数セグメントにまたがる場合は、各セグメントの訳を順に読んだときに自然につながるように分担してください。ただし各セグメントの訳を空にしないでください。
4. 「直前の対訳」「後続の原文」は文脈の参考情報です。翻訳対象に含めないでください。
5. 入力された全IDについて、必ず1件ずつ訳を出力してください。
6. 訳文には説明・注釈・原文・引用符を含めないでください。"""

    val BRIEF_SCHEMA: JSONObject = JSONObject(
        """
        {"type":"OBJECT","properties":{
          "summary":{"type":"STRING"},
          "style":{"type":"STRING"},
          "glossary":{"type":"ARRAY","items":{"type":"OBJECT","properties":{
            "source":{"type":"STRING"},"japanese":{"type":"STRING"}},"required":["source","japanese"]}}
        },"required":["summary","style","glossary"]}
        """.trimIndent()
    )

    val TRANSLATION_SCHEMA: JSONObject = JSONObject(
        """
        {"type":"ARRAY","items":{"type":"OBJECT","properties":{
          "index":{"type":"INTEGER"},"japanese":{"type":"STRING"}},"required":["index","japanese"]}}
        """.trimIndent()
    )

    fun buildBriefPrompt(videoTitle: String, segments: List<TimedSegment>): String {
        val fullText = segments.joinToString(" ") { it.originalText.trim() }
        val truncated = fullText.length > MAX_BRIEF_SOURCE_CHARS
        val source = if (truncated) fullText.take(MAX_BRIEF_SOURCE_CHARS) else fullText
        return buildString {
            append("以下はYouTube動画「").append(videoTitle).append("」の英語字幕全文です")
            if (truncated) append("（長いため冒頭部分のみ）")
            append("。日本語吹き替え翻訳の準備として、次の3点をJSONで出力してください。\n")
            append("- summary: 動画の内容・話者・トーンの要約（日本語、200字以内）\n")
            append("- style: 吹き替えの文体指定（例: 「です・ます調、親しみやすい解説口調」）\n")
            append("- glossary: 固有名詞・専門用語・繰り返し出る重要語と、その日本語訳（最大40件。人名・製品名はカタカナ表記）\n\n")
            append("【字幕全文】\n").append(source)
        }
    }

    fun parseBrief(rawText: String): VideoBrief? = try {
        val obj = JSONObject(stripCodeFence(rawText))
        val glossaryArr = obj.optJSONArray("glossary") ?: JSONArray()
        val glossary = (0 until glossaryArr.length()).mapNotNull { i ->
            val item = glossaryArr.optJSONObject(i) ?: return@mapNotNull null
            val src = item.optString("source").trim()
            val ja = item.optString("japanese").trim()
            if (src.isNotBlank() && ja.isNotBlank()) src to ja else null
        }
        VideoBrief(
            summary = obj.optString("summary").trim(),
            style = obj.optString("style").trim(),
            glossary = glossary
        )
    } catch (_: Exception) {
        null
    }

    fun buildChunkPrompt(
        videoTitle: String,
        brief: VideoBrief?,
        previousPairs: List<TimedSegment>,
        targets: List<TimedSegment>,
        followingSources: List<String>,
        retryReasons: Map<Int, String> = emptyMap()
    ): String = buildString {
        append("【動画タイトル】\n").append(videoTitle).append("\n\n")
        if (brief != null) {
            if (brief.summary.isNotBlank()) append("【動画の概要】\n").append(brief.summary).append("\n\n")
            if (brief.style.isNotBlank()) append("【文体】\n").append(brief.style).append("\n\n")
            if (brief.glossary.isNotEmpty()) {
                append("【用語集】\n")
                brief.glossary.forEach { (src, ja) -> append(src).append(" → ").append(ja).append("\n") }
                append("\n")
            }
        }
        if (previousPairs.isNotEmpty()) {
            append("【直前の対訳（参考・翻訳不要）】\n")
            previousPairs.forEach { append("EN: ").append(it.originalText).append("\nJA: ").append(it.translatedText).append("\n") }
            append("\n")
        }
        if (retryReasons.isNotEmpty()) {
            append("【再翻訳の依頼】以下のセグメントは前回の訳に問題がありました。理由を踏まえて訳し直してください。\n\n")
        }
        append("【翻訳対象セグメント】\n")
        targets.forEach { seg ->
            append("[ID:").append(seg.index).append("] 制限時間: ")
            append(String.format(Locale.US, "%.1f", seg.durationSec)).append("秒 / 最大文字数: ")
            append(seg.maxRecommendedJapaneseChars).append("字")
            retryReasons[seg.index]?.let { append(" / 前回の問題: ").append(it) }
            append("\nEN: ").append(seg.originalText).append("\n")
        }
        if (followingSources.isNotEmpty()) {
            append("\n【後続の原文（参考・翻訳不要）】\n")
            followingSources.forEach { append(it).append("\n") }
        }
    }

    fun parseTranslations(rawText: String): Map<Int, String> {
        val clean = stripCodeFence(rawText)
        val array = try {
            when {
                clean.startsWith("[") -> JSONArray(clean)
                clean.startsWith("{") -> JSONObject(clean).let { it.optJSONArray("translations") ?: it.optJSONArray("segments") }
                else -> null
            }
        } catch (_: Exception) {
            null
        } ?: return emptyMap()

        val result = mutableMapOf<Int, String>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            if (!item.has("index")) continue
            val idx = item.optInt("index", -1)
            val ja = item.optString("japanese").trim()
            if (idx >= 0 && ja.isNotBlank()) result[idx] = ja
        }
        return result
    }

    /**
     * Gemini 音声文字起こし応答を発話セグメントへ変換
     * 開始時刻順に並べ、訳文対応付けのキーとなる index は連番で振り直す（モデル出力の index は重複・欠番があり得る）
     */
    fun parseAsrSegments(rawText: String): List<TimedSegment> {
        val clean = stripCodeFence(rawText)
        val array = try {
            when {
                clean.startsWith("[") -> JSONArray(clean)
                clean.startsWith("{") -> JSONObject(clean).let { it.optJSONArray("segments") ?: it.optJSONArray("captions") }
                else -> null
            }
        } catch (_: Exception) {
            null
        } ?: return emptyList()

        val raw = (0 until array.length()).mapNotNull { i ->
            val item = array.optJSONObject(i) ?: return@mapNotNull null
            val text = item.optString("originalText").trim()
            if (text.isBlank()) return@mapNotNull null
            val startMs = item.optLong("startMs", 0L).coerceAtLeast(0L)
            val endMs = item.optLong("endMs", 0L).let { if (it <= startMs) startMs + 3000L else it }
            Triple(startMs, endMs, text)
        }.sortedBy { it.first }

        return raw.mapIndexed { i, (start, end, text) ->
            TimedSegment(index = i, startMs = start, endMs = end, originalText = text)
        }
    }

    /** 訳文の欠落・非日本語・尺超過を検出 */
    fun findProblems(targets: List<TimedSegment>, translations: Map<Int, String>): List<Problem> =
        targets.mapNotNull { seg ->
            val ja = translations[seg.index]
            when {
                ja.isNullOrBlank() -> Problem(seg, "訳文が欠落していました")
                !isSubstantiallyJapanese(ja) -> Problem(seg, "日本語になっていませんでした")
                JapaneseSpeechTiming.estimateMorae(ja) > seg.maxMorae * OVERLONG_TOLERANCE ->
                    Problem(seg, "読み上げが尺に収まりません（${spokenLength(ja)}字）。${seg.maxRecommendedJapaneseChars}字以内に短縮してください")
                else -> null
            }
        }

    /** 読み上げ長の目安（空白・句読点・記号を除いた文字数） */
    fun spokenLength(text: String): Int = text.count { !it.isWhitespace() && !isPunctuation(it) }

    fun isSubstantiallyJapanese(text: String): Boolean = JapaneseText.isSubstantiallyJapanese(text)

    private fun isPunctuation(c: Char): Boolean =
        c in "、。，．・！？!?,.…「」『』（）()〜—:：;；\"'" || Character.getType(c) == Character.OTHER_PUNCTUATION.toInt()

    private fun stripCodeFence(raw: String): String =
        raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
}

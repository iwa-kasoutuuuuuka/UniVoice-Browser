package com.univoice.browser.batch

import android.os.Build
import android.text.Html
import android.util.Log
import android.util.Xml
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

/**
 * YouTube timedtext (JSON3 & XML) パーサー
 * ネイティブ OkHttp や WebView から取得した字幕トラックを自然な発話セグメントに変換・整形
 */
object YouTubeTimedTextParser {
    private const val TAG = "YouTubeTimedTextParser"

    data class RawCaptionEvent(
        val startMs: Long,
        val endMs: Long,
        val text: String
    )

    /**
     * JSON3 形式の timedtext を解析
     */
    fun parseJson3(jsonStr: String, isManual: Boolean = false): List<TimedSegment> {
        if (jsonStr.isBlank()) return emptyList()
        val rawList = mutableListOf<RawCaptionEvent>()
        try {
            val root = JSONObject(jsonStr)
            val events = root.optJSONArray("events") ?: return emptyList()
            for (i in 0 until events.length()) {
                val ev = events.getJSONObject(i)
                val tStartMs = ev.optLong("tStartMs", -1L)
                if (tStartMs < 0L) continue
                val dDurationMs = ev.optLong("dDurationMs", 2500L)
                val segs = ev.optJSONArray("segs") ?: continue
                val sb = StringBuilder()
                for (j in 0 until segs.length()) {
                    val s = segs.getJSONObject(j)
                    sb.append(s.optString("utf8", ""))
                }
                val sanitized = sanitizeCaption(sb.toString())
                if (sanitized.isNotBlank()) {
                    rawList.add(RawCaptionEvent(tStartMs, tStartMs + dDurationMs, sanitized))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "[UniVoiceBrowser] JSON3字幕パース警告: ${e.message}")
        }
        return mergeRawEvents(rawList, isManual)
    }

    /**
     * 標準 XML (timedtext) 形式を解析
     */
    fun parseXml(xmlStr: String, isManual: Boolean = false): List<TimedSegment> {
        if (xmlStr.isBlank()) return emptyList()
        val rawList = mutableListOf<RawCaptionEvent>()
        try {
            val parser = Xml.newPullParser()
            parser.setInput(StringReader(xmlStr))
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && parser.name == "text") {
                    val startSec = parser.getAttributeValue(null, "start")?.toDoubleOrNull() ?: 0.0
                    val durSec = parser.getAttributeValue(null, "dur")?.toDoubleOrNull() ?: 2.5
                    val startMs = (startSec * 1000).toLong()
                    val endMs = startMs + (durSec * 1000).toLong()
                    val textRaw = parser.nextText()
                    val unescaped = unescapeHtml(textRaw)
                    val sanitized = sanitizeCaption(unescaped)
                    if (sanitized.isNotBlank()) {
                        rawList.add(RawCaptionEvent(startMs, endMs, sanitized))
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            Log.w(TAG, "[UniVoiceBrowser] XML字幕パース警告: ${e.message}")
        }
        return mergeRawEvents(rawList, isManual)
    }

    /**
     * 細切れの字幕イベントを自然な文単位にマージ（ハイブリッド文結合処理）
     */
    fun mergeRawEvents(rawList: List<RawCaptionEvent>, isManual: Boolean = false): List<TimedSegment> {
        if (rawList.isEmpty()) return emptyList()
        val mergedList = mutableListOf<RawCaptionEvent>()
        var currentChunk: RawCaptionEvent? = null

        for (item in rawList) {
            if (currentChunk == null) {
                currentChunk = item
                continue
            }
            val gapMs = item.startMs - currentChunk.endMs
            val combinedDuration = item.endMs - currentChunk.startMs
            val isSentenceEnd = Regex("[.!?。！？]$").containsMatchIn(currentChunk.text)

            if (gapMs < 1200 && combinedDuration <= 5500 && !isSentenceEnd && currentChunk.text.length < 80) {
                currentChunk = RawCaptionEvent(
                    startMs = currentChunk.startMs,
                    endMs = maxOf(currentChunk.endMs, item.endMs),
                    text = "${currentChunk.text} ${item.text}".trim()
                )
            } else {
                var text = currentChunk.text
                if (!isManual && !Regex("[.!?。！？]$").containsMatchIn(text)) {
                    text += "."
                }
                mergedList.add(currentChunk.copy(text = text))
                currentChunk = item
            }
        }
        currentChunk?.let {
            var text = it.text
            if (!isManual && !Regex("[.!?。！？]$").containsMatchIn(text)) {
                text += "."
            }
            mergedList.add(it.copy(text = text))
        }

        return mergedList.mapIndexed { idx, item ->
            TimedSegment(
                index = idx,
                startMs = item.startMs,
                endMs = item.endMs,
                originalText = item.text
            )
        }
    }

    private fun unescapeHtml(raw: String): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                Html.fromHtml(raw, Html.FROM_HTML_MODE_LEGACY).toString()
            } else {
                @Suppress("DEPRECATION")
                Html.fromHtml(raw).toString()
            }
        } catch (_: Exception) {
            raw.replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
        }
    }

    fun sanitizeCaption(raw: String): String {
        return raw
            // 1. YouTube UIのゴミ文字列を除去 (設定ボタン、言語ラベル等)
            .replace(Regex("\\[?(?:英語|日本語|English|Japanese)?\\s*\\(?(?:自動生成|auto-generated)\\)?\\s*(?:を?クリックして設定)?\\]?", RegexOption.IGNORE_CASE), "")
            .replace(Regex("を?クリックして設定", RegexOption.IGNORE_CASE), "")
            // 2. 音響効果マーカータグ・BGM表記を除去 ([Music], [Applause], [Laughter], [音楽], [拍手] 等)
            .replace(Regex("\\[(?:Music|Applause|Laughter|Snickering|Cheering|Gasp|Sigh|Groan|Chuckle|Cough|Yawn|Throat-clearing|音楽|拍手|笑い|歓声|ため息|せき|くしゃみ|歓声と拍手)[^\\]]*\\]", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\((?:Music|Applause|Laughter|Snickering|Cheering|Gasp|Sigh|Groan|Chuckle|Cough|Yawn|Throat-clearing|音楽|拍手|笑い|歓声|ため息|せき|くしゃみ)[^\\)]*\\)", RegexOption.IGNORE_CASE), "")
            // 3. 音符記号や飾り文字を除去
            .replace(Regex("[♪♫♬♩#]+"), "")
            // 4. 空白の正規化
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}

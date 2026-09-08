package com.univoice.browser.transcript

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.univoice.browser.model.UniVoiceSubtitleCue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 視聴中にキャプチャ・翻訳された字幕履歴を保持・管理し、
 * 語学学習や復習用に Markdown / CSV 形式でエクスポートするリポジトリ
 */
class TranscriptRepository private constructor(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("univoice_transcripts", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val cueList = CopyOnWriteArrayList<UniVoiceSubtitleCue>()

    private val _cuesFlow = MutableStateFlow<List<UniVoiceSubtitleCue>>(emptyList())
    val cuesFlow: StateFlow<List<UniVoiceSubtitleCue>> = _cuesFlow.asStateFlow()

    companion object {
        @Volatile
        private var instance: TranscriptRepository? = null

        fun getInstance(context: Context): TranscriptRepository {
            return instance ?: synchronized(this) {
                instance ?: TranscriptRepository(context).also { instance = it }
            }
        }
    }

    init {
        loadHistory()
    }

    private fun loadHistory() {
        val json = prefs.getString("saved_cues", null)
        if (!json.isNullOrBlank()) {
            try {
                val type = object : TypeToken<List<UniVoiceSubtitleCue>>() {}.type
                val loaded: List<UniVoiceSubtitleCue> = gson.fromJson(json, type)
                cueList.addAll(loaded)
                _cuesFlow.value = cueList.toList()
            } catch (e: Exception) {
                // Ignore parse errors
            }
        }
    }

    /**
     * 新しい字幕チャンクを記録
     */
    fun addCue(cue: UniVoiceSubtitleCue) {
        if (cue.originalText.isBlank()) return
        // 重複チェック
        val exists = cueList.any { it.cleanText == cue.cleanText && Math.abs(it.startTimeMs - cue.startTimeMs) < 1500 }
        if (!exists) {
            cueList.add(cue)
            _cuesFlow.value = cueList.toList()
            saveToPrefs()
        }
    }

    fun clearAll() {
        cueList.clear()
        _cuesFlow.value = emptyList()
        prefs.edit().remove("saved_cues").apply()
    }

    private fun saveToPrefs() {
        // 最大500件まで保持
        val toSave = cueList.takeLast(500)
        prefs.edit().putString("saved_cues", gson.toJson(toSave)).apply()
    }

    /**
     * タイムスタンプのフォーマット (例: 01:23)
     */
    private fun formatTimestamp(ms: Long): String {
        val seconds = (ms / 1000) % 60
        val minutes = (ms / (1000 * 60)) % 60
        val hours = ms / (1000 * 60 * 60)
        return if (hours > 0) {
            String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
        }
    }

    /**
     * Markdown形式でエクスポート
     */
    fun exportAsMarkdown(): String {
        val sb = StringBuilder()
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.JAPAN).format(Date())
        sb.append("# UniVoice Browser 日英対訳スクリプト\n")
        sb.append("作成日時: ").append(now).append("\n\n")
        sb.append("| 時間 | 原文字幕 (英語) | 翻訳 (日本語) |\n")
        sb.append("| :--- | :--- | :--- |\n")

        for (cue in cueList) {
            val time = formatTimestamp(cue.startTimeMs)
            val en = cue.cleanText.replace("|", "\\|")
            val ja = (cue.translatedText ?: "").replace("|", "\\|")
            sb.append("| ").append(time).append(" | ").append(en).append(" | ").append(ja).append(" |\n")
        }
        return sb.toString()
    }

    /**
     * CSV形式でエクスポート
     */
    fun exportAsCsv(): String {
        val sb = StringBuilder()
        sb.append("\"時間\",\"原文字幕\",\"日本語翻訳\"\n")
        for (cue in cueList) {
            val time = formatTimestamp(cue.startTimeMs)
            val en = cue.cleanText.replace("\"", "\"\"")
            val ja = (cue.translatedText ?: "").replace("\"", "\"\"")
            sb.append("\"$time\",\"$en\",\"$ja\"\n")
        }
        return sb.toString()
    }
}

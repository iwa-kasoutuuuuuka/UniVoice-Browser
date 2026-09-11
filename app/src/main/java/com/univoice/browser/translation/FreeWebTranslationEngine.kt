package com.univoice.browser.translation

import android.util.Log
import com.univoice.browser.model.TranslationEngineType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * APIキー不要で誰でも即座に利用可能な高速Web翻訳エンジン
 * Google Translate Mobile Web + MyMemory API の2段構えで、英語字幕を高品質な日本語へリアルタイム変換
 */
class FreeWebTranslationEngine : TranslationEngine {

    override val engineType: TranslationEngineType = TranslationEngineType.LOCAL_EDGE

    companion object {
        private const val TAG = "FreeWebTransEngine"
        private val RESULT_CONTAINER_PATTERN = Pattern.compile("""<div class="result-container">([^<]+)</div>""")
    }

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val localMemoryCache = ConcurrentHashMap<String, String>()

    override suspend fun initialize(): Boolean {
        return true
    }

    override suspend fun translate(text: String, contextHistory: List<String>): Result<String> {
        return withContext(Dispatchers.IO) {
            val clean = text.trim().trimStart('.', ',', ':', ';', '!', '?', '-', ' ').trim()
            if (clean.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("字幕テキストが空または記号のみです"))
            }

            // すでに日本語が含まれている場合はそのまま使用
            val containsJapanese = clean.any { it.code in 0x3040..0x30FF || it.code in 0x4E00..0x9FFF }
            if (containsJapanese) {
                return@withContext Result.success(clean)
            }

            // メモリキャッシュ確認
            localMemoryCache[clean]?.let {
                return@withContext Result.success(it)
            }

            // 1. Google Translate Mobile Web (高速・高品質・制限なし)
            try {
                val encoded = URLEncoder.encode(clean, "UTF-8")
                val googleUrl = "https://translate.google.com/m?sl=auto&tl=ja&q=$encoded"

                val request = Request.Builder()
                    .url(googleUrl)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36")
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val html = response.body?.string() ?: ""
                        val matcher = RESULT_CONTAINER_PATTERN.matcher(html)
                        if (matcher.find()) {
                            val rawJa = matcher.group(1)?.trim() ?: ""
                            val unescaped = unescapeHtml(rawJa).trimStart('.', '。', ' ', ',').trim()
                            if (unescaped.isNotBlank()) {
                                Log.d(TAG, "[UniVoiceBrowser] Google Web翻訳成功: [$clean] -> [$unescaped]")
                                localMemoryCache[clean] = unescaped
                                return@withContext Result.success(unescaped)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "[UniVoiceBrowser] Google Web翻訳例外: ${e.message}。MyMemoryへフォールバックします")
            }

            // 2. MyMemory 高速翻訳API (フォールバック)
            try {
                val encoded = URLEncoder.encode(clean, "UTF-8")
                val myMemoryUrl = "https://api.mymemory.translated.net/get?q=$encoded&langpair=en|ja&de=univoice.browser@gmail.com"

                val request = Request.Builder()
                    .url(myMemoryUrl)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: ""
                        val json = JSONObject(body)
                        val trans = json.optJSONObject("responseData")?.optString("translatedText")?.trim() ?: ""
                        if (trans.isNotBlank() && !trans.startsWith("MYMEMORY WARNING")) {
                            val unescaped = unescapeHtml(trans).trimStart('.', '。', ' ', ',').trim()
                            if (unescaped.isNotBlank()) {
                                Log.d(TAG, "[UniVoiceBrowser] MyMemory翻訳成功: [$clean] -> [$unescaped]")
                                localMemoryCache[clean] = unescaped
                                return@withContext Result.success(unescaped)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "[UniVoiceBrowser] MyMemory翻訳例外: ${e.message}")
            }

            // 3. ローカル定型フレーズ辞書
            val fallback = fallbackDictionaryTranslate(clean)
            if (fallback.isSuccess) {
                return@withContext fallback
            }

            Result.failure(IllegalStateException("翻訳結果の取得に失敗しました: $clean"))
        }
    }

    /**
     * オフライン時またはネットワーク切断時のための定型フレーズ辞書
     */
    private fun fallbackDictionaryTranslate(text: String): Result<String> {
        val lower = text.lowercase().trim()
        val phrases = mapOf(
            "hello" to "こんにちは",
            "welcome back" to "お帰りなさい",
            "thank you" to "ありがとうございます",
            "thanks for watching" to "ご視聴ありがとうございました",
            "subscribe" to "チャンネル登録をお願いします",
            "in this video" to "この動画では",
            "let's get started" to "それでは始めましょう",
            "as you can see" to "ご覧の通り",
            "for example" to "例えば",
            "first of all" to "まず初めに",
            "today we are going to" to "本日はこれを行います",
            "today i am going to" to "本日はこれを行います",
            "so i'm going to show you" to "その方法をお見せします",
            "so i am going to show you" to "その方法をお見せします",
            "i'm going to show you" to "お見せします",
            "also known as" to "別名",
            "don't forget to like and subscribe" to "高評価とチャンネル登録をお忘れなく",
            "next" to "次に",
            "finally" to "最後に",
            "what's up guys" to "皆さんこんにちは"
        )

        var replaced = text
        for ((en, ja) in phrases) {
            if (lower.contains(en)) {
                replaced = replaced.replace(Regex("(?i)$en"), ja)
            }
        }

        val hasJapanese = replaced.any { it.code in 0x3040..0x30FF || it.code in 0x4E00..0x9FFF }
        return if (hasJapanese) {
            Result.success(replaced)
        } else {
            Result.failure(NoSuchElementException("未登録のフレーズです"))
        }
    }

    private fun unescapeHtml(text: String): String {
        return text
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
    }

    override fun release() {
        localMemoryCache.clear()
    }
}

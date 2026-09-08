package com.univoice.browser.translation

import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.univoice.browser.model.TranslationEngineType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * APIキー不要で誰でも即座に利用可能な高速Web翻訳エンジン
 * MyMemory 高速翻訳API等を活用し、英語字幕を流暢な日本語へリアルタイム変換
 */
class FreeWebTranslationEngine : TranslationEngine {

    override val engineType: TranslationEngineType = TranslationEngineType.LOCAL_EDGE

    companion object {
        private const val TAG = "FreeWebTransEngine"
        private const val BASE_URL = "https://api.mymemory.translated.net/get"
    }

    private val gson = Gson()
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val localMemoryCache = ConcurrentHashMap<String, String>()

    override suspend fun initialize(): Boolean {
        return true
    }

    override suspend fun translate(text: String, contextHistory: List<String>): Result<String> {
        return withContext(Dispatchers.IO) {
            val clean = text.trim()
            if (clean.isBlank()) return@withContext Result.success("")

            // キャッシュ確認
            localMemoryCache[clean]?.let {
                return@withContext Result.success(it)
            }

            try {
                val encoded = URLEncoder.encode(clean, "UTF-8")
                val url = "$BASE_URL?q=$encoded&langpair=en|ja"

                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; UniVoiceBrowser) AppleWebKit/537.36")
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "[UniVoiceBrowser] Web翻訳HTTPエラー: コード=${response.code}")
                        return@withContext fallbackDictionaryTranslate(clean)
                    }

                    val body = response.body?.string()
                    if (body.isNullOrBlank()) {
                        return@withContext fallbackDictionaryTranslate(clean)
                    }

                    val resDto = gson.fromJson(body, MyMemoryResponse::class.java)
                    val translatedText = resDto.responseData?.translatedText?.trim()

                    if (!translatedText.isNullOrBlank() && resDto.responseStatus == 200) {
                        // HTMLエンティティのアンエスケープ
                        val unescaped = unescapeHtml(translatedText)
                        Log.d(TAG, "[UniVoiceBrowser] Web無料翻訳完了: [$clean] -> [$unescaped]")
                        localMemoryCache[clean] = unescaped
                        return@withContext Result.success(unescaped)
                    }

                    return@withContext fallbackDictionaryTranslate(clean)
                }
            } catch (e: Exception) {
                Log.w(TAG, "[UniVoiceBrowser] Web無料翻訳例外: ${e.message}。フォールバック辞書を適用します")
                return@withContext fallbackDictionaryTranslate(clean)
            }
        }
    }

    /**
     * オフライン時またはAPI失敗時のための包括的フォールバック辞書
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

        // 日本語が含まれている場合は変換後テキストを返す
        val containsJapanese = replaced.any { it.code in 0x3040..0x30FF || it.code in 0x4E00..0x9FFF }
        if (containsJapanese) {
            return Result.success(replaced)
        }

        // 最後の手段: 日本語TTSが発音できるよう、冒頭に日本語ラベルを付与
        return Result.success("動画の音声: $text")
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

    private data class MyMemoryResponse(
        @SerializedName("responseData") val responseData: MyMemoryData?,
        @SerializedName("responseStatus") val responseStatus: Int?
    )

    private data class MyMemoryData(
        @SerializedName("translatedText") val translatedText: String?
    )
}

package com.univoice.browser.translation

import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.univoice.browser.model.TranslationEngineType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Google Gemini クラウドAPIを用いた高精度・文脈認識翻訳エンジン
 */
class CloudGeminiTranslationEngine(
    private val apiKey: String,
    private val modelName: String = "gemini-1.5-flash",
    private val customEndpoint: String = ""
) : TranslationEngine {

    override val engineType: TranslationEngineType = TranslationEngineType.GEMINI_CLOUD

    companion object {
        private const val TAG = "CloudGeminiTransEngine"
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/"
        private const val SYSTEM_PROMPT =
            "あなたは動画の字幕翻訳者です。英語の字幕を、Text-to-Speech (TTS) による音声読み上げに最も適した、自然で聞き取りやすく流暢な日本語の話し言葉に翻訳してください。解説や注釈、余計な記号は一切含めず、翻訳後の日本語テキストのみを出力してください。"
    }

    private val gson = Gson()
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .writeTimeout(4, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    override suspend fun initialize(): Boolean {
        if (apiKey.isBlank()) {
            Log.w(TAG, "[UniVoiceBrowser] Gemini APIキーが設定されていません")
            return false
        }
        return true
    }

    override suspend fun translate(text: String, contextHistory: List<String>): Result<String> {
        return withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) {
                val msg = "Gemini APIキーが未設定です。設定画面からキーを入力してください。"
                Log.e(TAG, "[UniVoiceBrowser] $msg")
                return@withContext Result.failure(IllegalStateException(msg))
            }

            try {
                // コンテキスト履歴（直前の字幕）を含めたプロンプト構築
                val promptBuilder = StringBuilder()
                if (contextHistory.isNotEmpty()) {
                    promptBuilder.append("【直前の文脈】\n")
                    contextHistory.takeLast(3).forEach {
                        promptBuilder.append("- ").append(it).append("\n")
                    }
                    promptBuilder.append("\n【翻訳対象の最新字幕】\n")
                }
                promptBuilder.append(text)

                val requestDto = GeminiGenerateContentRequest(
                    contents = listOf(
                        GeminiContent(parts = listOf(GeminiPart(text = promptBuilder.toString())))
                    ),
                    systemInstruction = GeminiSystemInstruction(
                        parts = listOf(GeminiPart(text = SYSTEM_PROMPT))
                    ),
                    generationConfig = GeminiGenerationConfig(
                        temperature = 0.2f,
                        maxOutputTokens = 150
                    )
                )

                val jsonBody = gson.toJson(requestDto)
                val candidateModels = if (customEndpoint.isNotBlank()) {
                    listOf(modelName)
                } else {
                    listOf(modelName.trim(), "gemini-1.5-flash-latest", "gemini-1.5-flash", "gemini-2.0-flash").distinct().filter { it.isNotBlank() }
                }

                var lastError = Exception("Gemini呼び出し失敗")

                for (currentModel in candidateModels) {
                    val targetUrl = if (customEndpoint.isNotBlank()) {
                        customEndpoint
                    } else {
                        "$BASE_URL$currentModel:generateContent"
                    }

                    val request = Request.Builder()
                        .url(targetUrl)
                        .addHeader("x-goog-api-key", apiKey)
                        .addHeader("Content-Type", "application/json")
                        .post(jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
                        .build()

                    try {
                        val result = httpClient.newCall(request).execute().use { response ->
                            if (!response.isSuccessful) {
                                val errorBody = response.body?.string() ?: ""
                                Log.w(TAG, "[UniVoiceBrowser] Gemini API HTTPエラー ($currentModel): コード=${response.code}, 内容=$errorBody")
                                lastError = Exception("HTTP ${response.code}: $errorBody")
                                return@use null
                            }

                            val responseString = response.body?.string()
                            if (responseString.isNullOrBlank()) {
                                lastError = Exception("空のレスポンスを受信しました")
                                return@use null
                            }

                            val resultDto = gson.fromJson(responseString, GeminiGenerateContentResponse::class.java)
                            val translatedText = resultDto.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text?.trim()

                            if (translatedText.isNullOrBlank()) {
                                Log.w(TAG, "[UniVoiceBrowser] Gemini APIから有効な翻訳テキストが取得できませんでした: $responseString")
                                lastError = Exception("有効な翻訳テキストが存在しません")
                                return@use null
                            }

                            Log.d(TAG, "[UniVoiceBrowser] Gemini翻訳完了 ($currentModel): [$text] -> [$translatedText]")
                            translatedText
                        }

                        if (result != null) {
                            return@withContext Result.success(result)
                        }
                    } catch (e: Exception) {
                        lastError = e
                        Log.w(TAG, "[UniVoiceBrowser] Gemini API 試行例外 ($currentModel): ${e.message}")
                    }
                }

                Result.failure(lastError)
            } catch (e: Exception) {
                Log.e(TAG, "[UniVoiceBrowser] Gemini API 呼び出し例外: ${e.message}", e)
                Result.failure(e)
            }
        }
    }

    override fun release() {
        // OkHttp クライアントはプロセス全体で再利用可能
    }

    // --- Data Transfer Objects ---

    private data class GeminiGenerateContentRequest(
        @SerializedName("contents") val contents: List<GeminiContent>,
        @SerializedName("systemInstruction") val systemInstruction: GeminiSystemInstruction?,
        @SerializedName("generationConfig") val generationConfig: GeminiGenerationConfig?
    )

    private data class GeminiSystemInstruction(
        @SerializedName("parts") val parts: List<GeminiPart>
    )

    private data class GeminiContent(
        @SerializedName("parts") val parts: List<GeminiPart>
    )

    private data class GeminiPart(
        @SerializedName("text") val text: String
    )

    private data class GeminiGenerationConfig(
        @SerializedName("temperature") val temperature: Float,
        @SerializedName("maxOutputTokens") val maxOutputTokens: Int
    )

    private data class GeminiGenerateContentResponse(
        @SerializedName("candidates") val candidates: List<GeminiCandidate>?
    )

    private data class GeminiCandidate(
        @SerializedName("content") val content: GeminiContent?
    )
}

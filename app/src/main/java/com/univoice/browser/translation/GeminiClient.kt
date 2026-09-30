package com.univoice.browser.translation

import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Gemini API のモデル名解決
 * ※ gemini-1.5 系は提供終了、gemini-2.0 系は 2026-06-01 に提供終了済み (ai.google.dev/gemini-api/docs/deprecations)
 */
object GeminiModels {
    const val DEFAULT = "gemini-3.8-flash"

    private val FALLBACKS = listOf(
        "gemini-3.8-flash",
        "gemini-flash-latest",
        "gemini-3.5-flash-lite",
        "gemini-2.5-flash"
    )

    fun isRetired(model: String): Boolean {
        val m = model.trim().lowercase()
        return m.startsWith("gemini-1.") || m.startsWith("gemini-2.0") || m == "gemini-pro"
    }

    /** 設定値が空または提供終了モデルの場合は既定モデルへ置換 */
    fun resolveConfigured(model: String?): String =
        model?.trim()?.takeIf { it.isNotBlank() && !isRetired(it) } ?: DEFAULT

    /** 設定モデルを先頭に、現行フォールバックモデルを重複なく並べた試行順 */
    fun candidates(configured: String?): List<String> =
        (listOf(resolveConfigured(configured)) + FALLBACKS).distinct()
}

/**
 * Gemini generateContent 呼び出しの共通クライアント
 * モデルのフォールバック、thinking 設定の互換リトライ、思考パートを除いた本文抽出を一元化する
 */
class GeminiClient(
    private val apiKey: String,
    private val httpClient: OkHttpClient,
    private val customEndpoint: String = ""
) {
    companion object {
        private const val TAG = "GeminiClient"
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/"
        private const val FILES_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/"
        private const val UPLOAD_URL = "https://generativelanguage.googleapis.com/upload/v1beta/files"

        /** thinkingLevel 非対応と判明したモデル（以降の呼び出しで失敗往復を省く） */
        private val thinkingUnsupportedModels: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

        /** 別モデルで再試行する価値があるか（モデル未提供・レート制限・サーバー障害のみ） */
        internal fun isRetryableWithOtherModel(code: Int): Boolean = code == 404 || code == 429 || code >= 500

        fun textPart(text: String): JSONObject = JSONObject().put("text", text)

        /** candidates[0] の本文パートを連結して返す (thought パートは除外) */
        internal fun extractText(responseBody: String): String {
            val root = JSONObject(responseBody)
            val parts = root.optJSONArray("candidates")
                ?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts") ?: return ""
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                val part = parts.optJSONObject(i) ?: continue
                if (part.optBoolean("thought", false)) continue
                sb.append(part.optString("text", ""))
            }
            return sb.toString().trim()
        }
    }

    data class GenerateRequest(
        val userParts: List<JSONObject>,
        val systemInstruction: String? = null,
        val temperature: Double = 0.2,
        val maxOutputTokens: Int? = null,
        /** 指定時は application/json + responseSchema で構造化出力を強制 */
        val responseSchema: JSONObject? = null,
        /** Gemini 3.x の thinkingLevel (例: "low")。非対応モデルでは自動的に外して再試行する */
        val thinkingLevel: String? = null
    )

    suspend fun generateText(configuredModel: String?, request: GenerateRequest): Result<String> {
        if (apiKey.isBlank()) {
            return Result.failure(IllegalStateException("Gemini APIキーが未設定です。設定画面からキーを入力してください。"))
        }
        val models = if (customEndpoint.isNotBlank()) {
            listOf(GeminiModels.resolveConfigured(configuredModel))
        } else {
            GeminiModels.candidates(configuredModel)
        }

        // 本文（音声の inline base64 を含むと数十MB）はモデル・リトライ間で共有し、thinking有無ごとに1回だけ生成
        val bodies = HashMap<Boolean, okhttp3.RequestBody>(2)
        fun bodyFor(useThinking: Boolean) = bodies.getOrPut(useThinking) {
            buildBody(request, useThinking).toByteArray(Charsets.UTF_8)
                .toRequestBody("application/json; charset=utf-8".toMediaType())
        }

        var lastError: Exception = IllegalStateException("Gemini呼び出し失敗")
        for (model in models) {
            var useThinking = request.thinkingLevel != null && model !in thinkingUnsupportedModels
            while (true) {
                val url = customEndpoint.ifBlank { "$BASE_URL$model:generateContent" }
                val httpRequest = Request.Builder()
                    .url(url)
                    .addHeader("x-goog-api-key", apiKey)
                    .post(bodyFor(useThinking))
                    .build()

                val (code, body) = try {
                    httpClient.executeAsync(httpRequest).use { resp -> resp.code to resp.body?.string().orEmpty() }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.w(TAG, "[UniVoiceBrowser] Gemini 通信例外 ($model): ${e.message}")
                    lastError = e
                    break
                }

                if (code in 200..299) {
                    val text = try { extractText(body) } catch (e: Exception) { "" }
                    if (text.isNotBlank()) return Result.success(text)
                    Log.w(TAG, "[UniVoiceBrowser] Gemini 空応答 ($model): ${body.take(300)}")
                    lastError = IllegalStateException("Geminiから有効な応答が得られませんでした ($model)")
                    break
                }
                if (code == 400 && useThinking && body.contains("thinking", ignoreCase = true)) {
                    Log.i(TAG, "[UniVoiceBrowser] $model は thinkingLevel 非対応のため設定を外して再試行")
                    thinkingUnsupportedModels.add(model)
                    useThinking = false
                    continue
                }
                Log.w(TAG, "[UniVoiceBrowser] Gemini HTTPエラー ($model): $code ${body.take(300)}")
                lastError = IllegalStateException("HTTP $code ($model): ${body.take(300)}")
                // リクエスト自体の問題（サイズ超過・不正・認証）は別モデルでも失敗するため、再送せず終了
                if (!isRetryableWithOtherModel(code)) return Result.failure(lastError)
                break
            }
        }
        return Result.failure(lastError)
    }

    /**
     * File API へ音声等をアップロード（大容量ファイルを inline base64 にせずストリーム送信）
     * @return (fileUri, name)
     */
    suspend fun uploadFile(file: java.io.File, mimeType: String): Pair<String, String> {
        val metadataJson = JSONObject().put("file", JSONObject().put("displayName", file.name)).toString()
        val multipartBody = okhttp3.MultipartBody.Builder()
            .setType(okhttp3.MultipartBody.FORM)
            .addPart(
                okhttp3.Headers.headersOf("Content-Type", "application/json"),
                metadataJson.toRequestBody("application/json".toMediaType())
            )
            .addPart(
                okhttp3.Headers.headersOf("Content-Type", mimeType),
                file.asRequestBody(mimeType.toMediaType())
            )
            .build()
        val req = Request.Builder()
            .url(UPLOAD_URL)
            .addHeader("x-goog-api-key", apiKey)
            .post(multipartBody)
            .build()
        httpClient.executeAsync(req).use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("Gemini File API アップロード失敗: HTTP ${resp.code}")
            }
            val fileObj = JSONObject(resp.body?.string().orEmpty()).getJSONObject("file")
            return fileObj.getString("uri") to fileObj.getString("name")
        }
    }

    /** アップロード済みファイルの削除（失敗しても48時間で自動削除されるため握りつぶす） */
    suspend fun deleteFile(name: String) {
        try {
            val req = Request.Builder()
                .url("$FILES_BASE_URL$name")
                .addHeader("x-goog-api-key", apiKey)
                .delete()
                .build()
            httpClient.executeAsync(req).close()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "[UniVoiceBrowser] Gemini アップロードファイル削除失敗: ${e.message}")
        }
    }

    private fun buildBody(request: GenerateRequest, useThinking: Boolean): String {
        val root = JSONObject()
        root.put("contents", JSONArray().put(JSONObject().apply {
            put("role", "user")
            put("parts", JSONArray().apply { request.userParts.forEach { put(it) } })
        }))
        request.systemInstruction?.let {
            root.put("systemInstruction", JSONObject().put("parts", JSONArray().put(textPart(it))))
        }
        root.put("generationConfig", JSONObject().apply {
            put("temperature", request.temperature)
            request.maxOutputTokens?.let { put("maxOutputTokens", it) }
            request.responseSchema?.let {
                put("responseMimeType", "application/json")
                put("responseSchema", it)
            }
            if (useThinking && request.thinkingLevel != null) {
                put("thinkingConfig", JSONObject().put("thinkingLevel", request.thinkingLevel))
            }
        })
        return root.toString()
    }
}

/** OkHttp 呼び出しをキャンセル可能な suspend 関数として実行（キャンセル後に届いた応答は確実に close） */
internal suspend fun OkHttpClient.executeAsync(request: Request): okhttp3.Response = suspendCancellableCoroutine { cont ->
    val call = newCall(request)
    cont.invokeOnCancellation { call.cancel() }
    call.enqueue(object : okhttp3.Callback {
        override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }
        override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
            if (cont.isActive) cont.resume(response) else response.close()
        }
    })
}

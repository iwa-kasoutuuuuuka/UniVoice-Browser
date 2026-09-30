package com.univoice.browser.translation

import android.util.Log
import com.univoice.browser.model.TranslationEngineType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Google Gemini クラウドAPIを用いた高精度・文脈認識翻訳エンジン
 * 直前の対訳（原文→訳文）と動画タイトルをプロンプトに含め、文体・訳語の一貫性を保つ
 */
class CloudGeminiTranslationEngine(
    private val apiKey: String,
    private val modelName: String = GeminiModels.DEFAULT,
    customEndpoint: String = ""
) : TranslationEngine {

    override val engineType: TranslationEngineType = TranslationEngineType.GEMINI_CLOUD

    companion object {
        private const val TAG = "CloudGeminiTransEngine"
        private const val MAX_CONTEXT_PAIRS = 6

        internal const val SYSTEM_PROMPT = """あなたはYouTube動画のリアルタイム吹き替え翻訳者です。英語の字幕を、日本語音声合成(TTS)で読み上げる自然で聞き取りやすい日本語の話し言葉に翻訳してください。
【ルール】
1. 「直前の対訳」がある場合、その文体（です・ます調など）、一人称・呼称、固有名詞や専門用語の訳語を必ず踏襲し、一貫させてください。
2. 字幕は文の途中で区切られていることがあります。直前の文脈から自然につながる日本語にしてください。ただし原文に無い内容は付け足さないでください。
3. 読み上げやすさを優先し、冗長な言い回しは簡潔にしてください。
4. 出力は翻訳後の日本語テキストのみとし、説明・注釈・引用符・原文は含めないでください。"""

        /** ユーザープロンプト（動画タイトル・直前の対訳・翻訳対象）を構築 */
        internal fun buildUserPrompt(text: String, context: TranslationContext): String {
            val sb = StringBuilder()
            context.videoTitle?.takeIf { it.isNotBlank() }?.let {
                sb.append("【動画タイトル】\n").append(it).append("\n\n")
            }
            val pairs = context.previousPairs.takeLast(MAX_CONTEXT_PAIRS)
            if (pairs.isNotEmpty()) {
                sb.append("【直前の対訳（古い順）】\n")
                pairs.forEach { (src, ja) -> sb.append("EN: ").append(src).append("\nJA: ").append(ja).append("\n") }
                sb.append("\n")
            }
            sb.append("【翻訳対象の字幕】\n").append(text)
            return sb.toString()
        }

        /** モデルが付けがちな前置き・引用符を除去 */
        internal fun cleanOutput(raw: String): String {
            var t = raw.trim().removePrefix("JA:").removePrefix("訳:").trim()
            val quotePairs = listOf("「" to "」", "\"" to "\"", "『" to "』")
            for ((open, close) in quotePairs) {
                // 文全体を1組の引用符で囲んでいる場合のみ除去（開閉が同じ記号 " は計2個、異なる記号は閉じ側1個）
                val expectedCloseCount = if (open == close) 2 else 1
                if (t.length >= 2 && t.startsWith(open) && t.endsWith(close) && t.count { it.toString() == close } == expectedCloseCount) {
                    t = t.substring(open.length, t.length - close.length).trim()
                }
            }
            return t
        }
    }

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(4, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val client = GeminiClient(apiKey, httpClient, customEndpoint)

    override suspend fun initialize(): Boolean {
        if (apiKey.isBlank()) {
            Log.w(TAG, "[UniVoiceBrowser] Gemini APIキーが設定されていません")
            return false
        }
        return true
    }

    override suspend fun translate(text: String, contextHistory: List<String>): Result<String> =
        translate(text, TranslationContext())

    override suspend fun translate(text: String, context: TranslationContext): Result<String> = withContext(Dispatchers.IO) {
        val request = GeminiClient.GenerateRequest(
            userParts = listOf(GeminiClient.textPart(buildUserPrompt(text, context))),
            systemInstruction = SYSTEM_PROMPT,
            temperature = 0.2,
            // thinking トークンも上限に含まれるため、訳文が途切れない余裕を確保
            maxOutputTokens = 1024,
            // リアルタイム用途のため思考量は最小限に抑えて遅延を削減
            thinkingLevel = "low"
        )
        client.generateText(modelName, request).map { cleanOutput(it) }.onSuccess {
            Log.d(TAG, "[UniVoiceBrowser] Gemini翻訳完了: [$text] -> [$it]")
        }
    }

    override fun release() {
        // OkHttp クライアントはプロセス全体で再利用可能
    }
}

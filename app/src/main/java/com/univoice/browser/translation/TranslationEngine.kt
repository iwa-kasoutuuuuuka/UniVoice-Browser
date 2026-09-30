package com.univoice.browser.translation

import com.univoice.browser.model.TranslationEngineType

/**
 * 字幕翻訳エンジンの共通インターフェース
 */
interface TranslationEngine {

    val engineType: TranslationEngineType

    /**
     * エンジンの初期化（モデルロードやクライアントセットアップ）
     */
    suspend fun initialize(): Boolean

    /**
     * 字幕テキストを日本語に翻訳
     * @param text 原文字幕（主に英語）
     * @param contextHistory 直前の文脈履歴（先読みバッファや過去の字幕）
     * @return 翻訳後の日本語文字列、または失敗時のResult
     */
    suspend fun translate(text: String, contextHistory: List<String> = emptyList()): Result<String>

    /**
     * 文脈情報（直前の対訳・動画タイトル）付きで翻訳
     * 文脈を活用できないエンジンは原文履歴のみを渡す既定実装にフォールバックする
     */
    suspend fun translate(text: String, context: TranslationContext): Result<String> =
        translate(text, context.previousPairs.map { it.first })

    /**
     * リソース解放
     */
    fun release()
}

/**
 * 翻訳時の文脈情報
 * @param previousPairs 直前に確定した (原文, 訳文) の組。古い順
 * @param videoTitle 動画タイトル（話題・固有名詞の手がかり）
 */
data class TranslationContext(
    val previousPairs: List<Pair<String, String>> = emptyList(),
    val videoTitle: String? = null
)

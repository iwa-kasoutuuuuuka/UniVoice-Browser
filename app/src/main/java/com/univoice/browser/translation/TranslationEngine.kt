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
     * リソース解放
     */
    fun release()
}

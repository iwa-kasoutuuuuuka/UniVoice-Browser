package com.univoice.browser.adblock

import android.net.Uri
import android.util.Log
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

/**
 * 高速ネットワーク層 広告ブロックエンジン
 * WebView の shouldInterceptRequest において、広告ドメイン・トラッカー・解析エンドポイントを
 * サブミリ秒で検知・遮断し、通信帯域とバッテリーを保護します。
 */
object AdBlockEngine {

    private const val TAG = "UniVoiceAdBlock"

    // 広告・トラッキング配信ホストの完全一致ブラックリスト
    private val BLOCKED_HOSTS = hashSetOf(
        "googleads.g.doubleclick.net",
        "pubads.g.doubleclick.net",
        "securepubads.g.doubleclick.net",
        "pagead2.googlesyndication.com",
        "adservice.google.com",
        "tpc.googlesyndication.com",
        "ad.doubleclick.net",
        "static.doubleclick.net",
        "stats.g.doubleclick.net",
        "cm.g.doubleclick.net",
        "admob.com",
        "applovin.com",
        "unityads.unity3d.com",
        "vungle.com",
        "chartboost.com",
        "ironsrc.com",
        "inmobi.com",
        "amazon-adsystem.com",
        "aax.amazon-adsystem.com",
        "scorecardresearch.com",
        "sb.scorecardresearch.com",
        "criteo.com",
        "static.criteo.net",
        "taboola.com",
        "outbrain.com",
        "adnxs.com",
        "ads.youtube.com"
    )

    // ドメイン末尾一致で判定するサフィックス
    private val BLOCKED_HOST_SUFFIXES = arrayOf(
        "doubleclick.net",
        "googlesyndication.com",
        "google-analytics.com",
        "adservice.google.com"
    )

    // YouTube 特有の広告トラッキング・統計パス
    private val BLOCKED_PATH_PATTERNS = arrayOf(
        "/api/stats/ads",
        "/pagead/",
        "/ptracking"
    )

    /**
     * 与えられた URL が広告・不要トラフィックであるかを判定
     * ※動画本編ストリーム（*.googlevideo.com）は誤遮断を徹底回避
     */
    fun shouldBlock(url: String?): Boolean {
        if (url.isNullOrBlank()) return false

        val lowerUrl = url.lowercase()

        // 最重要保護: YouTube 動画ストリーム本体は絶対にブロックしない
        if (lowerUrl.contains("googlevideo.com")) {
            return false
        }

        try {
            val uri = Uri.parse(url)
            val host = uri.host?.lowercase() ?: ""

            // 1. 完全一致ホスト判定
            if (BLOCKED_HOSTS.contains(host)) {
                Log.d(TAG, "[AdBlock] 広告ホストを遮断しました: $host")
                return true
            }

            // 2. ホストサフィックス判定
            for (suffix in BLOCKED_HOST_SUFFIXES) {
                if (host == suffix || host.endsWith(".$suffix")) {
                    Log.d(TAG, "[AdBlock] 広告ドメインを遮断しました: $host")
                    return true
                }
            }

            // 3. YouTube 広告・トラッキングパス判定
            val path = uri.path ?: ""
            for (pattern in BLOCKED_PATH_PATTERNS) {
                if (path.contains(pattern)) {
                    Log.d(TAG, "[AdBlock] YouTube広告パスを遮断しました: $path")
                    return true
                }
            }
        } catch (e: Exception) {
            // パース失敗時はブロックせず安全側にフォールバック
        }

        return false
    }

    /**
     * 遮断時に返却する空の WebResourceResponse を生成
     */
    fun createEmptyResponse(): WebResourceResponse {
        return WebResourceResponse(
            "text/plain",
            "UTF-8",
            200,
            "OK",
            emptyMap(),
            ByteArrayInputStream(ByteArray(0))
        )
    }
}

package com.univoice.browser.js

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import com.univoice.browser.model.UniVoiceSubtitleCue

/**
 * WebView 内の JavaScript とネイティブ Android コードを安全に橋渡しするインターフェース
 */
class UniVoiceJSInterface(
    private val getCurrentUrlCallback: () -> String?,
    private val onSubtitleReceivedCallback: (UniVoiceSubtitleCue) -> Unit,
    private val onVideoStateChangedCallback: (isPlaying: Boolean, currentTimeMs: Long) -> Unit,
    private val onAudioSuppressedCallback: (Boolean) -> Unit,
    private val onCaptionStateChangedCallback: (Boolean) -> Unit = {}
) {

    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        const val INTERFACE_NAME = "UniVoiceBridge"
        private const val TAG = "UniVoiceJSInterface"
        private const val MAX_SUBTITLE_LENGTH = 1000
    }

    /**
     * オリジンの正当性を検証 (YouTubeドメインのみ許可)
     */
    private fun isOriginAuthorized(): Boolean {
        val currentUrl = getCurrentUrlCallback()
        val authorized = YouTubeScriptInjector.isYouTubeUrl(currentUrl)
        if (!authorized) {
            Log.w(TAG, "[Security Warning] 不正なオリジンからのJSBridge呼び出しをブロックしました: $currentUrl")
        }
        return authorized
    }

    /**
     * JavaScript 側でキャプチャされた時間情報付き字幕を受信
     * @param startTimeMs 字幕開始ミリ秒
     * @param endTimeMs 字幕終了ミリ秒
     * @param text 検出された字幕文字列
     */
    @JavascriptInterface
    fun onSubtitleReceived(startTimeMs: Long, endTimeMs: Long, text: String?) {
        if (!isOriginAuthorized()) return
        if (text.isNullOrBlank()) return

        // 異常に長い入力文字列を制限（DoS・過剰API課金・メモリ消費防止）
        val sanitized = text.take(MAX_SUBTITLE_LENGTH).trim()
        if (sanitized.isEmpty()) return

        val cue = UniVoiceSubtitleCue(
            startTimeMs = startTimeMs,
            endTimeMs = if (endTimeMs > startTimeMs) endTimeMs else startTimeMs + 3000L,
            originalText = sanitized
        )

        Log.d(TAG, "[UniVoiceBrowser] 字幕受信: [${startTimeMs}ms - ${endTimeMs}ms] $sanitized")

        mainHandler.post {
            onSubtitleReceivedCallback(cue)
        }
    }

    /**
     * 動画の再生状態および再生位置の通知
     */
    @JavascriptInterface
    fun onVideoStateChanged(isPlaying: Boolean, currentTimeMs: Long) {
        if (!isOriginAuthorized()) return
        Log.v(TAG, "[UniVoiceBrowser] 再生状態変更: isPlaying=$isPlaying, position=${currentTimeMs}ms")
        mainHandler.post {
            onVideoStateChangedCallback(isPlaying, currentTimeMs)
        }
    }

    /**
     * HTML5 <video> の元音声ミュートが正常に強制適用された際の通知
     */
    @JavascriptInterface
    fun onAudioSuppressed(isSuppressed: Boolean) {
        if (!isOriginAuthorized()) return
        Log.i(TAG, "[UniVoiceBrowser] 音声抑制ステータス: $isSuppressed (video.muted = true / volume = 0)")
        mainHandler.post {
            onAudioSuppressedCallback(isSuppressed)
        }
    }

    /**
     * 字幕(CC)の有効化状態変更の通知
     */
    @JavascriptInterface
    fun onCaptionStateChanged(isEnabled: Boolean) {
        if (!isOriginAuthorized()) return
        Log.d(TAG, "[UniVoiceBrowser] 字幕ステータス検知: isEnabled=$isEnabled")
        mainHandler.post {
            onCaptionStateChangedCallback(isEnabled)
        }
    }

    /**
     * JS 内からのデバッグログ受信用
     */
    @JavascriptInterface
    fun log(message: String?) {
        Log.d(TAG, "[UniVoiceBrowser-JS] $message")
    }
}

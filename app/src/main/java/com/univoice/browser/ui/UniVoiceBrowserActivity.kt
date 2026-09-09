package com.univoice.browser.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.EditorInfo
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.content.pm.ActivityInfo
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.univoice.browser.R
import com.univoice.browser.config.UniVoiceConfigManager
import com.univoice.browser.databinding.ActivityUnivoiceBrowserBinding
import com.univoice.browser.js.UniVoiceJSInterface
import com.univoice.browser.js.YouTubeScriptInjector
import com.univoice.browser.model.PipelineStatus
import com.univoice.browser.pipeline.UniVoicePipelineManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * UniVoice Browser メイン画面
 * フルスクリーンWebView、YouTube音声抑制＆字幕抽出JSの注入、リアルタイム日本語音声再生パイプラインを統括
 */
class UniVoiceBrowserActivity : AppCompatActivity() {

    private lateinit var binding: ActivityUnivoiceBrowserBinding
    private lateinit var configManager: UniVoiceConfigManager
    private lateinit var pipelineManager: UniVoicePipelineManager

    // フローティング字幕カード状態管理
    private var isCardMinimized: Boolean = false
    private var isDockedTop: Boolean = false

    // HTML5 全画面（最大化）再生管理
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var originalOrientation: Int = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    companion object {
        private const val TAG = "UniVoiceBrowserActivity"
        private const val DEFAULT_HOMEPAGE = "https://m.youtube.com/?hl=ja&gl=JP"
    }

    @Volatile
    private var currentLoadedUrl: String = DEFAULT_HOMEPAGE

    private var isNativeAudioMuted: Boolean = true

    override fun attachBaseContext(newBase: android.content.Context) {
        val locale = java.util.Locale.JAPANESE
        java.util.Locale.setDefault(locale)
        val config = android.content.res.Configuration(newBase.resources.configuration)
        config.setLocale(locale)
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUnivoiceBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)

        configManager = UniVoiceConfigManager.getInstance(this)
        pipelineManager = UniVoicePipelineManager(this, configManager)

        setupNavigationControls()
        setupSubtitleOverlayInteractions()
        setupWebView()
        applyOrientationLayout(resources.configuration.orientation)
        observePipelineStates()

        // 初期ページ読み込み (日本語クエリ・ヘッダー付き)
        val initialUrl = intent?.dataString ?: DEFAULT_HOMEPAGE
        loadInputUrl(initialUrl)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.dataString?.let { url ->
            loadInputUrl(url)
        }
    }

    /**
     * トップナビゲーションバーのイベント設定
     */
    private fun setupNavigationControls() {
        binding.btnNavBack.setOnClickListener {
            if (binding.wvBrowser.canGoBack()) {
                binding.wvBrowser.goBack()
            }
        }

        binding.btnNavForward.setOnClickListener {
            if (binding.wvBrowser.canGoForward()) {
                binding.wvBrowser.goForward()
            }
        }

        binding.btnNavReload.setOnClickListener {
            binding.wvBrowser.reload()
        }

        binding.btnTranscript.setOnClickListener {
            startActivity(Intent(this, UniVoiceTranscriptActivity::class.java))
        }

        binding.btnMuteToggle.setOnClickListener {
            isNativeAudioMuted = !isNativeAudioMuted
            val iconRes = if (isNativeAudioMuted) R.drawable.ic_volume_off else R.drawable.ic_volume_up
            binding.btnMuteToggle.setImageResource(iconRes)
            binding.wvBrowser.evaluateJavascript(YouTubeScriptInjector.buildToggleMuteScript(isNativeAudioMuted), null)
            val msg = if (isNativeAudioMuted) "原音を消音しました (AI音声のみ)" else "原音を出力しています"
            android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
        }

        binding.btnPip.setOnClickListener {
            enterPipMode()
        }

        // トップバーからの直接再生/一時停止コントロール
        binding.btnPlayPause.setOnClickListener {
            binding.wvBrowser.evaluateJavascript("window.__univoice_toggle_play_pause && window.__univoice_toggle_play_pause();", null)
        }

        // トップバーからの直接字幕(CC)ON/OFFトグル
        binding.btnToggleCc.setOnClickListener {
            // 字幕カードが閉じられていた場合は自動復帰
            if (binding.cardSubtitleOverlay.visibility == View.GONE) {
                binding.cardSubtitleOverlay.visibility = View.VISIBLE
                binding.cardRestoreSubtitle.visibility = View.GONE
            }
            binding.wvBrowser.evaluateJavascript("window.__univoice_toggle_cc && window.__univoice_toggle_cc();", null)
        }

        // トップバーからの直接全画面（最大化）トグル
        binding.btnFullscreen.setOnClickListener {
            if (customView != null) {
                hideCustomView()
            } else {
                binding.wvBrowser.evaluateJavascript("window.__univoice_toggle_fullscreen && window.__univoice_toggle_fullscreen();", null)
            }
        }

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, UniVoiceSettingsActivity::class.java))
        }

        // URL入力ハンドリング
        binding.etUrl.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                val input = v.text.toString().trim()
                loadInputUrl(input)
                true
            } else {
                false
            }
        }
    }

    /**
     * フローティング字幕カードの操作（ドラッグ移動・最小化・上下ドック移動・閉じる・再表示）
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupSubtitleOverlayInteractions() {
        // 1. エラー・ガイダンス通知の「閉じる」
        binding.btnDismissError.setOnClickListener {
            binding.layoutErrorOverlay.visibility = View.GONE
            pipelineManager.dismissGuidance()
        }

        // 2. 字幕カードを閉じる（非表示化）
        binding.btnCloseCard.setOnClickListener {
            binding.cardSubtitleOverlay.visibility = View.GONE
            binding.cardRestoreSubtitle.visibility = View.VISIBLE
        }

        // 3. 字幕カードの再表示
        binding.cardRestoreSubtitle.setOnClickListener {
            binding.cardSubtitleOverlay.visibility = View.VISIBLE
            binding.cardRestoreSubtitle.visibility = View.GONE
        }

        // 4. 最小化 / 展開トグル
        binding.btnMinimizeCard.setOnClickListener {
            toggleCardMinimized()
        }

        // 最小化時にカードヘッダーをタップした場合も展開
        binding.layoutCardHeader.setOnClickListener {
            if (isCardMinimized) {
                toggleCardMinimized()
            }
        }

        // 5. 上下位置のトグル切り替え（YouTube動画プレイヤー直下 ↔ 画面下部）
        binding.btnDockPosition.setOnClickListener {
            toggleDockPosition()
        }

        // 6. 自由ドラッグ移動ハンドリング (ドラッグ領域でのみスワイプ移動)
        setupDraggableSubtitleCard()
    }

    private fun toggleCardMinimized() {
        isCardMinimized = !isCardMinimized
        if (isCardMinimized) {
            binding.layoutSubtitleContent.visibility = View.GONE
            binding.btnMinimizeCard.setImageResource(R.drawable.ic_expand_less)
            binding.btnMinimizeCard.contentDescription = "展開"
        } else {
            binding.layoutSubtitleContent.visibility = View.VISIBLE
            binding.btnMinimizeCard.setImageResource(R.drawable.ic_expand_more)
            binding.btnMinimizeCard.contentDescription = "最小化"
        }
    }

    private fun toggleDockPosition() {
        val parentView = binding.cardSubtitleOverlay.parent as? View ?: return
        val density = resources.displayMetrics.density

        isDockedTop = !isDockedTop
        if (isDockedTop) {
            val navBottom = binding.layoutNavBar.bottom.toFloat()
            val approxVideoHeight = parentView.width.toFloat() * 9f / 16f
            val targetY = navBottom + approxVideoHeight + (10 * density)
            val targetTransY = targetY - binding.cardSubtitleOverlay.top.toFloat()

            binding.cardSubtitleOverlay.animate()
                .translationY(targetTransY)
                .translationX(0f)
                .setDuration(250)
                .start()
            binding.btnDockPosition.contentDescription = "下部に移動"
        } else {
            binding.cardSubtitleOverlay.animate()
                .translationY(0f)
                .translationX(0f)
                .setDuration(250)
                .start()
            binding.btnDockPosition.contentDescription = "上部に移動"
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDraggableSubtitleCard() {
        var startRawX = 0f
        var startRawY = 0f
        var initialTranslationX = 0f
        var initialTranslationY = 0f

        val dragTouchListener = View.OnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = event.rawX
                    startRawY = event.rawY
                    initialTranslationX = binding.cardSubtitleOverlay.translationX
                    initialTranslationY = binding.cardSubtitleOverlay.translationY
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.rawX - startRawX
                    val deltaY = event.rawY - startRawY

                    val parentView = binding.cardSubtitleOverlay.parent as? View ?: return@OnTouchListener false
                    val parentWidth = parentView.width.toFloat()
                    val parentHeight = parentView.height.toFloat()

                    val card = binding.cardSubtitleOverlay
                    val minTransX = -card.left.toFloat()
                    val maxTransX = parentWidth - card.right.toFloat()
                    val topLimit = binding.layoutNavBar.bottom.toFloat()
                    val minTransY = topLimit - card.top.toFloat()
                    val maxTransY = parentHeight - card.bottom.toFloat()

                    val targetX = (initialTranslationX + deltaX).coerceIn(minTransX, maxTransX)
                    val targetY = (initialTranslationY + deltaY).coerceIn(minTransY, maxTransY)

                    card.translationX = targetX
                    card.translationY = targetY
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                    true
                }
                else -> false
            }
        }

        binding.layoutDragTouchArea.setOnTouchListener(dragTouchListener)
    }

    private fun loadInputUrl(input: String) {
        if (input.isBlank()) return
        // 脆弱性対策: javascript: スキームの実行を遮断
        if (input.lowercase().startsWith("javascript:") || input.lowercase().startsWith("file:")) {
            Log.w(TAG, "[Security Alert] 不正なスキームの実行を遮断しました: $input")
            return
        }
        val url = when {
            input.startsWith("http://") || input.startsWith("https://") -> input
            input.contains(".") && !input.contains(" ") -> "https://$input"
            else -> "https://m.youtube.com/results?search_query=" + java.net.URLEncoder.encode(input, "UTF-8") + "&hl=ja&gl=JP"
        }
        val headers = mapOf(
            "Accept-Language" to "ja,ja-JP;q=0.9,en;q=0.8"
        )
        binding.wvBrowser.loadUrl(url, headers)
    }

    /**
     * ハードウェアアクセラレーション最適化 WebView のセットアップ
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val webView = binding.wvBrowser

        // Snapdragon 8 Gen 2 / Poco F6 Pro の GPU レイヤーアクセラレーションを明示適用
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        webView.isFocusable = true
        webView.isFocusableInTouchMode = true

        // YouTubeクッキー設定 (常に日本語ロケールと日本地域を適用)
        try {
            val cookieManager = android.webkit.CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(webView, true)
            cookieManager.setCookie("https://youtube.com", "PREF=hl=ja&gl=JP; domain=.youtube.com; path=/")
            cookieManager.setCookie("https://m.youtube.com", "PREF=hl=ja&gl=JP; domain=.youtube.com; path=/")
        } catch (e: Exception) {
            Log.w(TAG, "[UniVoiceBrowser] Cookie設定スキップ: ${e.message}")
        }

        val settings = webView.settings
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            // 脆弱性対策: ローカルファイルアクセスおよび混在コンテンツを厳格に無効化
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            useWideViewPort = true
            loadWithOverviewMode = true
            // タップ操作をZoomManagerに奪われないようズーム制御を無効化
            builtInZoomControls = false
            displayZoomControls = false
            setSupportZoom(false)
            javaScriptCanOpenWindowsAutomatically = true
            // モバイル版Chrome最新UAを明示指定 (WebView特有のVersion/4.0識別子を排除してYouTubeプレーヤーのフル操作を解放)
            userAgentString = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
        }

        // JavaScript ブリッジの登録 (オリジン検証用コールバックを注入: UIスレッド外からのWebViewアクセスを回避)
        val jsInterface = UniVoiceJSInterface(
            getCurrentUrlCallback = { currentLoadedUrl },
            onSubtitleReceivedCallback = { cue ->
                pipelineManager.onSubtitleReceived(cue)
            },
            onVideoStateChangedCallback = { isPlaying, currentTimeMs ->
                runOnUiThread {
                    binding.btnPlayPause.setImageResource(
                        if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow
                    )
                }
                if (!isPlaying) {
                    pipelineManager.stopAudio()
                }
            },
            onAudioSuppressedCallback = { isSuppressed ->
                Log.d(TAG, "[UniVoiceBrowser] ネイティブ音声抑制確認: $isSuppressed")
            },
            onCaptionStateChangedCallback = { isEnabled ->
                runOnUiThread {
                    binding.btnToggleCc.alpha = if (isEnabled) 1.0f else 0.5f
                }
                pipelineManager.onCaptionStateChanged(isEnabled)
            }
        )
        webView.addJavascriptInterface(jsInterface, UniVoiceJSInterface.INTERFACE_NAME)

        // クライアント設定
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                url?.let { currentLoadedUrl = it }
                binding.progressBar.visibility = View.VISIBLE
                binding.etUrl.setText(url)
                checkAndInjectYouTubeScripts(url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                url?.let { currentLoadedUrl = it }
                binding.progressBar.visibility = View.GONE
                checkAndInjectYouTubeScripts(url)
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                url?.let {
                    currentLoadedUrl = it
                    binding.etUrl.setText(it)
                    checkAndInjectYouTubeScripts(it)
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                // intent:// スキーム等のハンドリング (YouTubeアプリ遷移リンクをWeb内URLへ変換または無効化)
                if (url.startsWith("intent:")) {
                    try {
                        val intent = android.content.Intent.parseUri(url, android.content.Intent.URI_INTENT_SCHEME)
                        val fallbackUrl = intent.getStringExtra("browser_fallback_url")
                        if (!fallbackUrl.isNullOrBlank()) {
                            view?.loadUrl(fallbackUrl)
                            return true
                        }
                        val dataUri = intent.data
                        if (dataUri != null && (dataUri.scheme == "http" || dataUri.scheme == "https")) {
                            view?.loadUrl(dataUri.toString())
                            return true
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "intent scheme parse error: ${e.message}")
                    }
                    return true // 未知の外部アプリインテントでWebページエラーになるのを防ぐ
                }

                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    return true // javascript:, file:, market: 等の不正・不要スキームを抑止
                }

                return false // 通常のHTTP/HTTPSはWebView内で処理
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val requestUrl = request?.url?.toString()
                if (configManager.currentSettings.adBlockEnabled && com.univoice.browser.adblock.AdBlockEngine.shouldBlock(requestUrl)) {
                    return com.univoice.browser.adblock.AdBlockEngine.createEmptyResponse()
                }
                return super.shouldInterceptRequest(view, request)
            }
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                binding.progressBar.progress = newProgress
                if (newProgress == 100) {
                    binding.progressBar.visibility = View.GONE
                }
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                val msg = consoleMessage?.message() ?: ""
                Log.d(TAG, "[WebConsole ${consoleMessage?.messageLevel()}] $msg (${consoleMessage?.sourceId()}:${consoleMessage?.lineNumber()})")
                return true
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                Log.i(TAG, "[UniVoiceBrowser] WebChromeClient.onShowCustomView 受信")
                if (view != null) {
                    showCustomView(view, callback)
                }
            }

            override fun onHideCustomView() {
                Log.i(TAG, "[UniVoiceBrowser] WebChromeClient.onHideCustomView 受信")
                hideCustomView()
            }
        }
    }

    /**
     * YouTube URL判定と音声抑制・字幕取得・広告ブロックスクリプトの注入
     */
    private fun checkAndInjectYouTubeScripts(url: String?) {
        if (YouTubeScriptInjector.isYouTubeUrl(url)) {
            Log.i(TAG, "[UniVoiceBrowser] YouTube動画ページを検知しました。音声ミュート強制＆字幕インターセプト＆広告ブロックスクリプトを注入します: $url")
            val isAudioSuppression = configManager.currentSettings.audioSuppressionEnabled
            val isAdBlockEnabled = configManager.currentSettings.adBlockEnabled
            val jsPayload = YouTubeScriptInjector.buildInjectionScript(isAudioSuppression, isAdBlockEnabled)
            binding.wvBrowser.evaluateJavascript(jsPayload) { result ->
                Log.d(TAG, "[UniVoiceBrowser] スクリプト評価完了: $result")
            }
        }
    }

    /**
     * パイプライン状態と字幕のUI監視 (StateFlow)
     */
    private fun observePipelineStates() {
        // 動作モードバッジの更新
        lifecycleScope.launch {
            configManager.settingsFlow.collectLatest { settings ->
                binding.tvModeBadge.text = settings.currentMode.titleJapanese
                // 音声抑制設定が変わった場合、最新設定でスクリプト再適用
                checkAndInjectYouTubeScripts(binding.wvBrowser.url)
            }
        }

        // 字幕の更新
        lifecycleScope.launch {
            pipelineManager.currentCue.collectLatest { cue ->
                if (cue != null) {
                    binding.tvTranslatedSubtitle.text = cue.translatedText ?: cue.cleanText
                    binding.tvOriginalSubtitle.text = cue.cleanText
                    binding.tvOriginalSubtitle.visibility = View.VISIBLE
                } else {
                    binding.tvTranslatedSubtitle.text = getString(R.string.status_idle)
                    binding.tvOriginalSubtitle.visibility = View.GONE
                }
            }
        }

        // パイプラインステータスの更新
        lifecycleScope.launch {
            pipelineManager.currentStatus.collectLatest { status ->
                binding.tvPipelineStatus.text = status.titleJapanese
                val dotColor = when (status) {
                    PipelineStatus.PLAYING -> ContextCompat.getColor(this@UniVoiceBrowserActivity, R.color.primary)
                    PipelineStatus.TRANSLATING, PipelineStatus.SYNTHESIZING ->
                        ContextCompat.getColor(this@UniVoiceBrowserActivity, R.color.accent_amber)
                    PipelineStatus.ERROR_FALLBACK ->
                        ContextCompat.getColor(this@UniVoiceBrowserActivity, R.color.error_red)
                    else -> ContextCompat.getColor(this@UniVoiceBrowserActivity, R.color.accent_green)
                }
                binding.ivStatusDot.setColorFilter(dotColor)
            }
        }

        // 先読み件数バッジの更新
        lifecycleScope.launch {
            pipelineManager.prefetchedCountFlow.collectLatest { count ->
                binding.tvPrefetchCount.text = "先読み: ${count}件"
            }
        }

        // 非侵入的エラーおよび字幕ガイダンスのオーバーレイ表示制御
        lifecycleScope.launch {
            kotlinx.coroutines.flow.combine(
                pipelineManager.errorMessage,
                pipelineManager.captionGuidanceMessage
            ) { error, guidance ->
                error ?: guidance
            }.collectLatest { alertText ->
                if (alertText.isNullOrBlank()) {
                    binding.layoutErrorOverlay.visibility = View.GONE
                } else {
                    binding.layoutErrorOverlay.visibility = View.VISIBLE
                    binding.tvErrorMessage.text = alertText
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        applyOrientationLayout(newConfig.orientation)
    }

    /**
     * 画面の向き（縦画面・横画面）に応じたUI最適化
     */
    private fun applyOrientationLayout(orientation: Int) {
        val isLandscape = orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val density = resources.displayMetrics.density

        // 横画面時はナビゲーションバーをスリム化して動画領域を最大化
        val navLayoutParams = binding.layoutNavBar.layoutParams
        navLayoutParams.height = if (isLandscape) (44 * density).toInt() else (56 * density).toInt()
        binding.layoutNavBar.layoutParams = navLayoutParams

        // 横画面時はフローティング字幕オーバーレイの余白を縮小
        val cardLayoutParams = binding.cardSubtitleOverlay.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
        cardLayoutParams.bottomMargin = if (isLandscape) (8 * density).toInt() else (24 * density).toInt()
        cardLayoutParams.marginStart = if (isLandscape) (32 * density).toInt() else (16 * density).toInt()
        cardLayoutParams.marginEnd = if (isLandscape) (32 * density).toInt() else (16 * density).toInt()
        binding.cardSubtitleOverlay.layoutParams = cardLayoutParams

        // 画面回転時はドラッグによるオフセットをリセット
        binding.cardSubtitleOverlay.translationX = 0f
        binding.cardSubtitleOverlay.translationY = 0f
        isDockedTop = false
    }

    /**
     * HTML5 動画の全画面（最大化）表示ハンドリング
     */
    private fun showCustomView(view: View, callback: WebChromeClient.CustomViewCallback?) {
        if (customView != null) {
            hideCustomView()
            return
        }

        customView = view
        customViewCallback = callback
        originalOrientation = requestedOrientation

        binding.layoutNavBar.visibility = View.GONE
        binding.progressBar.visibility = View.GONE
        binding.wvBrowser.visibility = View.GONE

        binding.fullscreenContainer.apply {
            removeAllViews()
            addView(
                view,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            visibility = View.VISIBLE
        }

        binding.cardSubtitleOverlay.translationX = 0f
        binding.cardSubtitleOverlay.translationY = 0f
        isDockedTop = false
        binding.cardSubtitleOverlay.bringToFront()
        binding.btnFullscreen.setImageResource(R.drawable.ic_fullscreen_exit)
        setFullscreenImmersive(true)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        Log.i(TAG, "[UniVoiceBrowser] YouTube動画の最大化（全画面表示）を開始しました")
    }

    private fun hideCustomView() {
        if (customView == null) return

        binding.fullscreenContainer.apply {
            removeView(customView)
            visibility = View.GONE
        }

        binding.wvBrowser.visibility = View.VISIBLE
        binding.layoutNavBar.visibility = View.VISIBLE

        binding.cardSubtitleOverlay.translationX = 0f
        binding.cardSubtitleOverlay.translationY = 0f
        isDockedTop = false

        setFullscreenImmersive(false)
        binding.btnFullscreen.setImageResource(R.drawable.ic_fullscreen)

        customViewCallback?.onCustomViewHidden()
        customView = null
        customViewCallback = null
        requestedOrientation = originalOrientation

        Log.i(TAG, "[UniVoiceBrowser] YouTube動画の最大化を解除しました")
    }

    private fun setFullscreenImmersive(fullscreen: Boolean) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val controller = window.insetsController
            if (controller != null) {
                if (fullscreen) {
                    controller.hide(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
                    controller.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                } else {
                    controller.show(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
                }
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (fullscreen) {
                (android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                        or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
            } else {
                android.view.View.SYSTEM_UI_FLAG_VISIBLE
            }
        }
    }

    override fun onBackPressed() {
        if (customView != null) {
            hideCustomView()
            return
        }
        if (binding.wvBrowser.canGoBack()) {
            binding.wvBrowser.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onPause() {
        super.onPause()
        binding.wvBrowser.onPause()
        pipelineManager.stopAudio()
    }

    override fun onResume() {
        super.onResume()
        binding.wvBrowser.onResume()
    }

    /**
     * ピクチャー・イン・ピクチャー (PiP) モードの開始
     */
    private fun enterPipMode() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            try {
                val aspectRatio = android.util.Rational(16, 9)
                val params = android.app.PictureInPictureParams.Builder()
                    .setAspectRatio(aspectRatio)
                    .build()
                enterPictureInPictureMode(params)
                Log.i(TAG, "[UniVoiceBrowser] ピクチャー・イン・ピクチャー (PiP) モードを開始しました")
            } catch (e: Exception) {
                Log.e(TAG, "[UniVoiceBrowser] PiP開始エラー: ${e.message}", e)
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // ホームボタン押下時、YouTube再生中であれば自動でPiPへ移行
        if (YouTubeScriptInjector.isYouTubeUrl(binding.wvBrowser.url)) {
            enterPipMode()
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            // PiP時はナビゲーションバーを非表示にし、字幕表示をコンパクト化
            binding.layoutNavBar.visibility = View.GONE
            binding.progressBar.visibility = View.GONE
            binding.cardSubtitleOverlay.alpha = 0.85f
        } else {
            // 通常画面復帰時にナビゲーションバーを復元
            binding.layoutNavBar.visibility = View.VISIBLE
            binding.cardSubtitleOverlay.alpha = 1.0f
        }
    }

    override fun onDestroy() {
        hideCustomView()
        super.onDestroy()
        pipelineManager.release()
        binding.wvBrowser.destroy()
    }
}

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
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
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
    private var batchPipeline: com.univoice.browser.batch.BatchDownloadPipeline? = null
    private var batchJob: kotlinx.coroutines.Job? = null
    private var batchPlayer: com.univoice.browser.batch.BatchDubbingPlayer? = null
    private var completedBatchSegments: List<com.univoice.browser.batch.TimedSegment>? = null

    // BUG-C02: ページロード完了後に実行する吹き替え再生キュー
    private var pendingDubbingPlayback: (() -> Unit)? = null

    // バッチ字幕抽出の保留状態管理＆安全タイムアウト
    private var isBatchExtractionPending: Boolean = false
    private var isBatchNeedsSettings: Boolean = false

    // BUG-H02: ダウンロードリストダイアログ参照 (WindowLeaked防止)
    private var downloadedVideosDialog: com.google.android.material.bottomsheet.BottomSheetDialog? = null
    // BUG-H01: ダウンロードリストFlow監視ジョブ
    private var downloadedListJob: kotlinx.coroutines.Job? = null

    // フローティング字幕カード状態管理
    private var isCardMinimized: Boolean = false
    private var isDockedTop: Boolean = false

    // HTML5 全画面（最大化）再生管理
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var originalOrientation: Int = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    // Android 13+ (API 33) 通知パーミッション要求ランチャー (BUG-POT-008)
    private val notificationPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Log.w(TAG, "[UniVoiceBrowser] POST_NOTIFICATIONS 権限が拒否されました。バックグラウンド再生通知が表示されない可能性があります")
        }
    }

    private fun checkAndRequestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

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
        batchPlayer = com.univoice.browser.batch.BatchDubbingPlayer(this).apply {
            onSegmentChanged = { segment ->
                runOnUiThread {
                    binding.tvTranslatedSubtitle.text = segment.translatedText
                }
            }
            onPlaybackStateChanged = { isPlaying ->
                runOnUiThread {
                    if (isPlaying) {
                        binding.btnStartBatchDubbing.text = "⏸ 一時停止"
                    } else {
                        binding.btnStartBatchDubbing.text = "▶ 再開"
                    }
                }
            }
        }

        setupNavigationControls()
        setupSubtitleOverlayInteractions()
        setupWebView()
        applyOrientationLayout(resources.configuration.orientation)
        observePipelineStates()
        setupBackPressHandler()
        checkAndRequestNotificationPermission()

        // 初期ページ読み込み (日本語クエリ・ヘッダー付き)
        val initialUrl = intent?.dataString ?: DEFAULT_HOMEPAGE
        loadInputUrl(initialUrl)
    }

    private fun setupBackPressHandler() {
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (customView != null) {
                    hideCustomView()
                    return
                }
                // バッチ吹き替え再生・ジョブの停止と状態クリア
                batchJob?.cancel()
                batchJob = null
                batchPlayer?.stopDubbing()
                completedBatchSegments = null

                if (binding.wvBrowser.canGoBack()) {
                    binding.wvBrowser.goBack()
                } else {
                    isEnabled = false
                    finish()
                }
            }
        })
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

        binding.btnDownloadedList.setOnClickListener {
            showDownloadedVideosDialog()
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

        binding.btnBackgroundAudio.setOnClickListener {
            val current = configManager.currentSettings.backgroundPlaybackEnabled
            val newState = !current
            if (newState) {
                checkAndRequestNotificationPermission()
            }
            configManager.setBackgroundPlaybackEnabled(newState)
            updateBackgroundAudioButton(newState)
            val msg = if (newState) "画面消灯・バックグラウンド再生を有効にしました" else "バックグラウンド再生を無効にしました"
            android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
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

        // URL入力ハンドリング (GO, DONE, SEARCH, およびEnterキー押下を全捕捉)
        binding.etUrl.setOnEditorActionListener { v, actionId, event ->
            val isEnterDown = event != null && event.keyCode == android.view.KeyEvent.KEYCODE_ENTER && event.action == android.view.KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_SEARCH || isEnterDown) {
                val input = v.text.toString().trim()
                loadInputUrl(input)
                // キーボードを閉じる
                val imm = getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                imm?.hideSoftInputFromWindow(v.windowToken, 0)
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

        // 字幕カード上の字幕(CC)ON/OFFトグル（全画面モード時にも即座に操作可能）
        binding.btnCardToggleCc.setOnClickListener {
            binding.wvBrowser.evaluateJavascript("window.__univoice_toggle_cc && window.__univoice_toggle_cc();", null)
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

        // 6. ダウンロード徹底バッチ翻訳の「吹き替えを開始」/「日本語版再生」ボタン
        binding.btnStartBatchDubbing.setOnClickListener {
            if (isBatchNeedsSettings) {
                isBatchNeedsSettings = false
                startActivity(Intent(this, UniVoiceSettingsActivity::class.java))
                return@setOnClickListener
            }
            val player = batchPlayer
            val segments = completedBatchSegments
            if (player != null && segments != null && segments.isNotEmpty()) {
                // すでに日本語吹き替えが用意されている場合: 日本語版同期再生 / 一時停止トグル
                if (player.isActive) {
                    player.pauseDubbing()
                    binding.wvBrowser.evaluateJavascript("const v = document.querySelector('video'); if (v && !v.paused) v.pause();", null)
                } else {
                    // 日本語版再生開始: 動画の元音声を完全にミュートし、再生位置を取得して同期開始
                    binding.wvBrowser.evaluateJavascript("""
                        (function() {
                            const v = document.querySelector('video');
                            if (v) {
                                v.muted = true;
                                if (v.paused) v.play();
                                return Math.floor(v.currentTime * 1000);
                            }
                            return 0;
                        })();
                    """.trimIndent()) { resultStr ->
                        val posMs = resultStr?.replace("\"", "")?.toLongOrNull() ?: 0L
                        player.startDubbing(posMs)
                    }
                }
            } else {
                startBatchDubbingProcess()
            }
        }

        // 7. 自由ドラッグ移動ハンドリング (ドラッグ領域でのみスワイプ移動)
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
                // バッチ吹き替えプレイヤーが有効な場合はタイムスタンプ同期を優先
                batchPlayer?.onVideoPositionChanged(isPlaying, currentTimeMs)

                if (!isPlaying) {
                    // 動画停止時は音声再生中TTSのみ停止（キューや翻訳事前バッファは破棄せず維持し、一時停止中の先読みを継続）
                    pipelineManager.pauseAudioOutputOnly()
                } else {
                    if (batchPlayer?.isActive != true) {
                        pipelineManager.resumeAudioOutput()
                    }
                }
            },
            onAudioSuppressedCallback = { isSuppressed ->
                Log.d(TAG, "[UniVoiceBrowser] ネイティブ音声抑制確認: $isSuppressed")
            },
            onCaptionStateChangedCallback = { isEnabled ->
                runOnUiThread {
                    binding.btnToggleCc.alpha = if (isEnabled) 1.0f else 0.5f
                    binding.btnCardToggleCc.alpha = if (isEnabled) 1.0f else 0.5f
                }
                pipelineManager.onCaptionStateChanged(isEnabled)
            },
            onVideoNavigatedCallback = { url, videoId ->
                runOnUiThread {
                    // SPA動画切替時に前の動画のバッチ吹き替えを安全に破棄・停止
                    batchJob?.cancel()
                    batchJob = null
                    batchPlayer?.stopDubbing()
                    completedBatchSegments = null
                    isBatchNeedsSettings = false
                    isBatchExtractionPending = false
                    binding.btnStartBatchDubbing.isEnabled = true
                    binding.btnStartBatchDubbing.text = "吹き替えを開始"
                    binding.layoutBatchProgress.visibility = View.GONE

                    if (!url.isNullOrBlank()) {
                        currentLoadedUrl = url
                        if (!binding.etUrl.hasFocus()) {
                            binding.etUrl.setText(url)
                        }
                        updateBatchControlVisibility(url)
                    }
                    pipelineManager.resetForNewVideo(videoId)
                }
            },
            onBatchCaptionsExtractedCallback = { jsonPayload ->
                runOnUiThread { handleExtractedBatchCaptions(jsonPayload) }
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
                url?.let { 
                    currentLoadedUrl = it 
                    updateBatchControlVisibility(it)
                }
                binding.progressBar.visibility = View.GONE
                checkAndInjectYouTubeScripts(url)

                // BUG-C02: ページロード完了後に保留中の吹き替え再生を実行
                pendingDubbingPlayback?.let { action ->
                    pendingDubbingPlayback = null
                    // YouTube のプレイヤー初期化を待つために少し遅延
                    view?.postDelayed({ action() }, 1500)
                }
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                url?.let {
                    currentLoadedUrl = it
                    binding.etUrl.setText(it)
                    updateBatchControlVisibility(it)
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

                // tel:, mailto:, sms:, market: 等の安全な外部アプリ連携スキームを安全に委譲
                if (url.startsWith("tel:") || url.startsWith("mailto:") || url.startsWith("sms:") || url.startsWith("market:")) {
                    try {
                        val externalIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                        view?.context?.startActivity(externalIntent)
                    } catch (e: Exception) {
                        Log.w(TAG, "[UniVoiceBrowser] 外部アプリ起動スキップ: ${e.message}")
                    }
                    return true
                }

                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    return true // javascript:, file:, content: 等の不正・危険スキームを抑止
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

        val isDebuggable = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (isDebuggable) {
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
        // 動作モードバッジおよびバックグラウンド再生ボタンの更新
        lifecycleScope.launch {
            configManager.settingsFlow.collectLatest { settings ->
                binding.tvModeBadge.text = settings.currentMode.titleJapanese
                updateBackgroundAudioButton(settings.backgroundPlaybackEnabled)
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

        // 視聴スタイルに応じたバッチUI更新
        lifecycleScope.launch {
            configManager.settingsFlow.collectLatest {
                updateBatchControlVisibility(binding.wvBrowser.url)
            }
        }
    }

    /**
     * 動画URLと設定スタイルに応じてバッチ吹き替え開始UIの表示/非表示を切り替え
     */
    private fun updateBatchControlVisibility(url: String?) {
        val isBatchMode = configManager.currentSettings.executionStyle == com.univoice.browser.model.ExecutionStyle.BATCH_DOWNLOAD
        val isVideo = YouTubeScriptInjector.isVideoWatchUrl(url)

        runOnUiThread {
            if (isBatchMode && isVideo) {
                binding.layoutBatchControl.visibility = View.VISIBLE
                val approachName = configManager.currentSettings.batchApproach.titleJapanese
                binding.tvBatchDescription.text = "【$approachName】\nYouTubeの字幕有無に関係なく、AIが動画音声から文字起こし＆尺合わせ日本語吹き替えを生成します。"
            } else {
                binding.layoutBatchControl.visibility = View.GONE
            }
        }
    }

    /**
     * 案B: ユーザーが「吹き替えを開始」ボタンをタップした時に実行されるバッチ処理
     */
    private fun startBatchDubbingProcess() {
        val currentUrl = binding.wvBrowser.url
        if (!YouTubeScriptInjector.isVideoWatchUrl(currentUrl)) {
            android.widget.Toast.makeText(this, "動画再生ページで実行してください", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        isBatchExtractionPending = true
        isBatchNeedsSettings = false
        binding.btnStartBatchDubbing.isEnabled = false
        binding.btnStartBatchDubbing.text = "解析中..."
        binding.layoutBatchProgress.visibility = View.VISIBLE
        binding.pbBatchProgress.progress = 5
        binding.tvBatchProgressText.text = "YouTube字幕データの抽出を試行中..."

        // 安全タイマー: JavaScriptからの応答が8000ms以内に来ない場合の自動フォールバック
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (isBatchExtractionPending) {
                Log.w(TAG, "[UniVoiceBrowser] 字幕抽出スクリプトの応答タイムアウト(8000ms)。直接フォールバック処理を実行します")
                handleExtractedBatchCaptions(null)
            }
        }, 8000)

        // まずWebページ内の字幕データを抽出試行（字幕がある場合は最速かつ無料/API消費ゼロで高品質バッチ生成）
        binding.wvBrowser.evaluateJavascript(
            "if (typeof window.__univoice_extract_batch_captions === 'function') { window.__univoice_extract_batch_captions(); } else if (window.UniVoiceBridge && window.UniVoiceBridge.onBatchCaptionsExtracted) { window.UniVoiceBridge.onBatchCaptionsExtracted(''); }",
            null
        )
    }

    private fun handleExtractedBatchCaptions(jsonPayload: String?) {
        // 重複実行防止
        if (!isBatchExtractionPending && jsonPayload == null) return
        isBatchExtractionPending = false

        val currentUrl = binding.wvBrowser.url
        if (!YouTubeScriptInjector.isVideoWatchUrl(currentUrl)) return

        val parsedSegments = mutableListOf<com.univoice.browser.batch.TimedSegment>()
        if (!jsonPayload.isNullOrBlank()) {
            try {
                val jsonArray = org.json.JSONArray(jsonPayload)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    parsedSegments.add(
                        com.univoice.browser.batch.TimedSegment(
                            index = obj.optInt("index", i),
                            startMs = obj.optLong("startMs", 0L),
                            endMs = obj.optLong("endMs", 0L),
                            originalText = obj.optString("originalText", "")
                        )
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "[UniVoiceBrowser] バッチ字幕パース例外: ${e.message}")
            }
        }

        // 字幕なしの場合、かつ音声文字起こしモデル未配置の場合の案内ガード
        if (parsedSegments.isEmpty()) {
            val whisperModel = java.io.File(filesDir, "models/whisper_base.onnx")
            if (!whisperModel.exists() || whisperModel.length() == 0L) {
                isBatchNeedsSettings = true
                binding.btnStartBatchDubbing.isEnabled = true
                binding.btnStartBatchDubbing.text = "設定を開く"
                binding.layoutBatchProgress.visibility = View.VISIBLE
                binding.pbBatchProgress.progress = 0
                binding.tvBatchProgressText.text = "この動画にはYouTube字幕が付いていません。音声からのAI文字起こしを行うには、設定画面の『オンデバイスAIモデル管理』からWhisperモデルを配備してください。"
                return
            }
        }

        val videoId = YouTubeScriptInjector.extractVideoId(currentUrl)
        val videoTitle = binding.wvBrowser.title ?: "YouTube Video ($videoId)"
        val settings = configManager.currentSettings

        binding.btnStartBatchDubbing.isEnabled = false
        binding.btnStartBatchDubbing.text = "処理中..."
        binding.layoutBatchProgress.visibility = View.VISIBLE
        binding.pbBatchProgress.progress = 10
        binding.tvBatchProgressText.text = if (parsedSegments.isNotEmpty()) {
            "字幕抽出完了 (${parsedSegments.size}件)。尺合わせ翻訳と音声生成を開始..."
        } else {
            "字幕なし。AI音声解析と文字起こし準備を開始..."
        }

        val pipeline = com.univoice.browser.batch.BatchDownloadPipeline(
            context = this,
            geminiApiKey = settings.geminiApiKey,
            geminiModelName = settings.geminiModelName,
            batchApproach = settings.batchApproach,
            voiceGender = settings.voiceGender
        )
        batchPipeline = pipeline

        batchJob?.cancel()
        batchJob = lifecycleScope.launch {
            pipeline.jobStatus.collectLatest { status ->
                runOnUiThread {
                    binding.pbBatchProgress.progress = status.progressPercent
                    binding.tvBatchProgressText.text = status.statusMessageJapanese

                    // 3要素個別進捗の反映
                    binding.pbAudioProgress.progress = status.audioProgressPercent
                    binding.tvAudioProgressText.text = "${status.audioProgressPercent}%"

                    binding.pbTransProgress.progress = status.transProgressPercent
                    binding.tvTransProgressText.text = "${status.transProgressPercent}%"

                    binding.pbVideoProgress.progress = status.videoProgressPercent
                    binding.tvVideoProgressText.text = "${status.videoProgressPercent}%"

                    if (status.isCompleted) {
                        binding.btnStartBatchDubbing.isEnabled = true
                        binding.btnStartBatchDubbing.text = "▶ 日本語版再生"
                        binding.btnStartBatchDubbing.setBackgroundColor(android.graphics.Color.parseColor("#2E7D32"))
                        binding.tvTranslatedSubtitle.text = "✨ 日本語音声と動画の準備が完了しました！「▶ 日本語版再生」を押して視聴してください"
                    } else if (status.errorMessage != null) {
                        binding.btnStartBatchDubbing.isEnabled = true
                        binding.btnStartBatchDubbing.text = "再試行"
                        binding.tvBatchProgressText.text = "エラー: ${status.errorMessage}"
                    }
                }
            }
        }

        lifecycleScope.launch {
            val result = pipeline.executeBatchProcessing(
                videoId = videoId,
                videoTitle = videoTitle,
                rawCaptions = if (parsedSegments.isNotEmpty()) parsedSegments else null,
                audioStreamUrl = currentUrl
            )
            result.onSuccess { segments ->
                Log.i(TAG, "[UniVoiceBrowser] バッチ吹き替え生成完了: ${segments.size} セグメント")
                completedBatchSegments = segments
                batchPlayer?.loadSegments(segments)
                runOnUiThread {
                    binding.btnStartBatchDubbing.isEnabled = true
                    binding.btnStartBatchDubbing.text = "▶ 日本語版再生"
                    binding.btnStartBatchDubbing.setBackgroundColor(android.graphics.Color.parseColor("#2E7D32"))
                    binding.tvTranslatedSubtitle.text = "✨ 日本語音声と動画の準備が完了しました！「▶ 日本語版再生」を押して視聴してください"
                }
            }.onFailure { error ->
                Log.e(TAG, "[UniVoiceBrowser] バッチ吹き替え生成失敗: ${error.message}", error)
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

        // 横画面（フルスクリーン含む）時はフローティング字幕オーバーレイの余白を調整し、下部のシークバー/コントロール領域を遮らないようにする
        val cardLayoutParams = binding.cardSubtitleOverlay.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
        cardLayoutParams.bottomMargin = if (isLandscape) (48 * density).toInt() else (24 * density).toInt()
        cardLayoutParams.marginStart = if (isLandscape) (64 * density).toInt() else (16 * density).toInt()
        cardLayoutParams.marginEnd = if (isLandscape) (64 * density).toInt() else (16 * density).toInt()
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
        binding.wvBrowser.visibility = View.INVISIBLE

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
            isFocusable = true
            isFocusableInTouchMode = true
        }

        // Chromiumの動画コンテナ/SurfaceViewがタップやジェスチャーを拾えるようにフォーカスを付与
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        view.isClickable = true
        view.requestFocus()

        binding.cardSubtitleOverlay.translationX = 0f
        binding.cardSubtitleOverlay.translationY = 0f
        binding.cardSubtitleOverlay.translationZ = 100f
        binding.cardRestoreSubtitle.translationZ = 100f
        isDockedTop = false
        binding.cardSubtitleOverlay.bringToFront()
        binding.cardRestoreSubtitle.bringToFront()
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
        binding.cardSubtitleOverlay.translationZ = 0f
        binding.cardRestoreSubtitle.translationZ = 0f
        isDockedTop = false

        setFullscreenImmersive(false)
        binding.btnFullscreen.setImageResource(R.drawable.ic_fullscreen)

        customViewCallback?.onCustomViewHidden()
        customView = null
        customViewCallback = null
        // 端末の現在の物理センサー・ユーザー回転設定に従って復元
        requestedOrientation = if (originalOrientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
            originalOrientation
        } else {
            ActivityInfo.SCREEN_ORIENTATION_USER
        }

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


    private fun updateBackgroundAudioButton(enabled: Boolean) {
        runOnUiThread {
            binding.btnBackgroundAudio.alpha = if (enabled) 1.0f else 0.45f
        }
    }

    override fun onPause() {
        super.onPause()

        // PiPモード（小画面継続表示）またはActivity破棄中の場合はサービス起動をスキップ
        if (isInPictureInPictureMode || isFinishing) {
            return
        }

        val isBackgroundEnabled = configManager.currentSettings.backgroundPlaybackEnabled
        val isYouTube = YouTubeScriptInjector.isYouTubeUrl(binding.wvBrowser.url)

        if (isBackgroundEnabled && isYouTube) {
            Log.i(TAG, "[UniVoiceBrowser] バックグラウンド再生が有効なため、WebViewと音声パイプラインを停止させず維持します")
            com.univoice.browser.service.UniVoicePlaybackService.start(this)
        } else {
            binding.wvBrowser.onPause()
            pipelineManager.stopAudio()
            com.univoice.browser.service.UniVoicePlaybackService.stop(this)
        }
    }

    override fun onResume() {
        super.onResume()
        binding.wvBrowser.onResume()
        // フォアグラウンド復帰時はサービス停止
        com.univoice.browser.service.UniVoicePlaybackService.stop(this)
    }

    /**
     * ピクチャー・イン・ピクチャー (PiP) モードの開始
     */
    private fun enterPipMode() {
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

    /**
     * ダウンロード動画リストダイアログを表示
     */
    private fun showDownloadedVideosDialog() {
        val repo = com.univoice.browser.batch.DownloadedVideoRepository.getInstance(this)
        val dialogView = layoutInflater.inflate(R.layout.dialog_downloaded_videos, null)
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        dialog.setContentView(dialogView)
        downloadedVideosDialog = dialog // BUG-H02: 参照保持

        val rv = dialogView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvDownloadedVideos)
        val tvEmpty = dialogView.findViewById<TextView>(R.id.tvEmptyDownloadedList)
        val btnClose = dialogView.findViewById<ImageButton>(R.id.btnCloseDialog)

        btnClose.setOnClickListener { dialog.dismiss() }
        rv.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)

        val adapter = DownloadedVideoAdapter(
            onPlayClicked = { item ->
                dialog.dismiss()
                playDownloadedVideoDirectly(item)
            },
            onDeleteClicked = { item ->
                repo.deleteItem(item.videoId)
                android.widget.Toast.makeText(this, "キャッシュを削除しました: ${item.title}", android.widget.Toast.LENGTH_SHORT).show()
            }
        )
        rv.adapter = adapter

        // BUG-H01: Flow collectorをダイアログライフサイクルに紐付け
        downloadedListJob?.cancel()
        downloadedListJob = lifecycleScope.launch {
            repo.itemsFlow.collectLatest { list ->
                runOnUiThread {
                    adapter.submitList(list)
                    if (list.isEmpty()) {
                        tvEmpty.visibility = View.VISIBLE
                        rv.visibility = View.GONE
                    } else {
                        tvEmpty.visibility = View.GONE
                        rv.visibility = View.VISIBLE
                    }
                }
            }
        }

        // BUG-H01: ダイアログ閉じた時にFlow collectorをキャンセル
        dialog.setOnDismissListener {
            downloadedListJob?.cancel()
            downloadedListJob = null
            downloadedVideosDialog = null
        }

        dialog.show()
    }

    /**
     * ダウンロード完了動画をリストから直接再生
     */
    private fun playDownloadedVideoDirectly(item: com.univoice.browser.batch.DownloadedVideoItem) {
        val targetUrl = item.videoUrl.ifBlank { "https://www.youtube.com/watch?v=${item.videoId}" }
        Log.i(TAG, "[UniVoiceBrowser] ダウンロードリストから再生開始: ${item.title} ($targetUrl)")

        // 該当動画のキャッシュディレクトリからセグメント音声をロード
        val cacheDir = java.io.File(cacheDir, "batch_dubbing_cache/${item.videoId}")
        val loaded = batchPlayer?.loadFromCacheDirectory(cacheDir) ?: false

        if (loaded) {
            binding.btnStartBatchDubbing.isEnabled = true
            binding.btnStartBatchDubbing.text = "⏸ 一時停止"
            binding.btnStartBatchDubbing.setBackgroundColor(android.graphics.Color.parseColor("#2E7D32"))
            binding.tvTranslatedSubtitle.text = "▶ 日本語吹き替え版を再生中: ${item.title}"
        }

        // 再生開始をトリガーするラムダ
        val startPlayback: () -> Unit = {
            binding.wvBrowser.evaluateJavascript("""
                (function() {
                    const v = document.querySelector('video');
                    if (v) {
                        v.muted = true;
                        v.currentTime = 0;
                        if (v.paused) v.play();
                        return 0;
                    }
                    return 0;
                })();
            """.trimIndent()) {
                if (loaded) {
                    batchPlayer?.startDubbing(0L)
                }
            }
        }

        // BUG-C02: URLが異なる場合はページロード完了後に再生を実行
        if (binding.wvBrowser.url != targetUrl) {
            pendingDubbingPlayback = startPlayback
            loadInputUrl(targetUrl)
        } else {
            // 同じURLの場合は即座に再生
            startPlayback()
        }
    }

    // --- ダウンロード動画リスト用 Adapter ---
    private class DownloadedVideoAdapter(
        private val onPlayClicked: (com.univoice.browser.batch.DownloadedVideoItem) -> Unit,
        private val onDeleteClicked: (com.univoice.browser.batch.DownloadedVideoItem) -> Unit
    ) : androidx.recyclerview.widget.ListAdapter<com.univoice.browser.batch.DownloadedVideoItem, DownloadedVideoAdapter.ViewHolder>(DiffCallback) {

        object DiffCallback : androidx.recyclerview.widget.DiffUtil.ItemCallback<com.univoice.browser.batch.DownloadedVideoItem>() {
            override fun areItemsTheSame(oldItem: com.univoice.browser.batch.DownloadedVideoItem, newItem: com.univoice.browser.batch.DownloadedVideoItem) =
                oldItem.videoId == newItem.videoId

            override fun areContentsTheSame(oldItem: com.univoice.browser.batch.DownloadedVideoItem, newItem: com.univoice.browser.batch.DownloadedVideoItem) =
                oldItem == newItem
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_downloaded_video, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(getItem(position))
        }

        inner class ViewHolder(itemView: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(itemView) {
            private val tvTitle: TextView = itemView.findViewById(R.id.tvVideoTitle)
            private val tvBadge: TextView = itemView.findViewById(R.id.tvCompletionBadge)
            private val tvMeta: TextView = itemView.findViewById(R.id.tvMetaInfo)

            private val tvAudioPercent: TextView = itemView.findViewById(R.id.tvItemAudioPercent)
            private val pbAudio: ProgressBar = itemView.findViewById(R.id.pbItemAudioProgress)

            private val tvTransPercent: TextView = itemView.findViewById(R.id.tvItemTransPercent)
            private val pbTrans: ProgressBar = itemView.findViewById(R.id.pbItemTransProgress)

            private val tvVideoPercent: TextView = itemView.findViewById(R.id.tvItemVideoPercent)
            private val pbVideo: ProgressBar = itemView.findViewById(R.id.pbItemVideoProgress)

            private val btnPlay: com.google.android.material.button.MaterialButton = itemView.findViewById(R.id.btnPlayVideo)
            private val btnDelete: com.google.android.material.button.MaterialButton = itemView.findViewById(R.id.btnDeleteVideo)

            fun bind(item: com.univoice.browser.batch.DownloadedVideoItem) {
                tvTitle.text = item.title
                tvMeta.text = "動画ID: ${item.videoId} • ${if (item.totalSegments > 0) "${item.totalSegments}セグメント" else item.statusMessage}"

                tvAudioPercent.text = "${item.audioProgress}%"
                pbAudio.progress = item.audioProgress

                tvTransPercent.text = "${item.transProgress}%"
                pbTrans.progress = item.transProgress

                tvVideoPercent.text = "${item.videoProgress}%"
                pbVideo.progress = item.videoProgress

                if (item.isFullyCompleted) {
                    tvBadge.text = "完了 ✓"
                    tvBadge.setTextColor(android.graphics.Color.parseColor("#10B981"))
                    btnPlay.isEnabled = true
                    btnPlay.text = "▶ 日本語版再生"
                    btnPlay.alpha = 1.0f
                } else {
                    val avgProgress = (item.audioProgress + item.transProgress + item.videoProgress) / 3
                    tvBadge.text = "処理中 ${avgProgress}%"
                    tvBadge.setTextColor(android.graphics.Color.parseColor("#F59E0B"))
                    btnPlay.isEnabled = false
                    btnPlay.text = "処理中..."
                    btnPlay.alpha = 0.5f
                }

                btnPlay.setOnClickListener { onPlayClicked(item) }
                btnDelete.setOnClickListener { onDeleteClicked(item) }
            }
        }
    }

    override fun onDestroy() {
        hideCustomView()
        // BUG-H02: ダイアログが開いていればdismissしてWindowLeakedを防止
        downloadedVideosDialog?.dismiss()
        downloadedVideosDialog = null
        downloadedListJob?.cancel()
        downloadedListJob = null
        pendingDubbingPlayback = null
        com.univoice.browser.service.UniVoicePlaybackService.stop(this)
        batchPlayer?.release()
        batchPlayer = null
        pipelineManager.release()
        // BUG-M01: WebViewリーク防止 — 親から除去してから破棄
        (binding.wvBrowser.parent as? ViewGroup)?.removeView(binding.wvBrowser)
        binding.wvBrowser.destroy()
        super.onDestroy() // BUG-M01: super は最後に呼ぶ
    }
}

# 頻出修正テンプレート

コピー＆ペーストして使える、よくある修正パターン。

---

## T1: JavaBridgeスレッドからの安全なUI操作

```kotlin
// ❌ 危険 — JavaBridgeスレッドからUI操作でクラッシュ
onBatchCaptionsExtractedCallback = { jsonPayload ->
    handleExtractedBatchCaptions(jsonPayload)  // CalledFromWrongThreadException
}

// ✅ 安全
onBatchCaptionsExtractedCallback = { jsonPayload ->
    runOnUiThread { handleExtractedBatchCaptions(jsonPayload) }
}
```

---

## T2: CancellationException の正しいハンドリング

```kotlin
// ❌ 危険 — コルーチンのキャンセルが停止しない
} catch (e: Exception) {
    Log.e(TAG, "エラー: ${e.message}")
}

// ✅ 安全
} catch (e: Exception) {
    if (e is CancellationException) throw e  // キャンセルは必ず再スロー
    Log.e(TAG, "エラー: ${e.message}")
}
```

---

## T3: OkHttp suspendCancellableCoroutine 化

```kotlin
// ❌ 危険 — キャンセル不能・スレッドブロック
val response = client.newCall(request).execute()

// ✅ 安全 — コルーチンキャンセルに対応
private suspend fun OkHttpClient.executeSuspend(request: Request): okhttp3.Response =
    suspendCancellableCoroutine { cont ->
        val call = newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                if (cont.isActive) cont.resume(response)
            }
        })
    }
```

---

## T4: MediaPlayer の安全な解放

```kotlin
// ❌ 危険 — stop() 例外で release() がスキップされる
try {
    mediaPlayer?.stop()
    mediaPlayer?.release()
} catch (e: Exception) { }

// ✅ 安全 — 独立した try/catch で確実に解放
fun stopCurrentMediaPlayer() {
    try { mediaPlayer?.stop() } catch (e: Exception) { Log.w(TAG, "stop: ${e.message}") }
    try { mediaPlayer?.release() } catch (e: Exception) { Log.w(TAG, "release: ${e.message}") }
    mediaPlayer = null
}

// ✅ onErrorListener でも必ず release
mediaPlayer?.setOnErrorListener { mp, what, extra ->
    try { mp.release() } catch (e: Exception) { }
    mediaPlayer = null
    true
}

// ✅ isPreparing フラグで状態機械違反を防ぐ
private var isPreparing = false

fun playSegmentAudio(file: File) {
    mediaPlayer?.let { mp ->
        isPreparing = true
        mp.setDataSource(file.absolutePath)
        mp.setOnPreparedListener {
            isPreparing = false
            it.start()
        }
        mp.prepareAsync()
    }
}

// onVideoPositionChanged 内:
if (targetSegment != null && !isPreparing && mediaPlayer?.isPlaying != true) {
    playSegmentAudio(targetSegment.audioFile)
}
```

---

## T5: ダイアログのライフサイクル管理

```kotlin
// Activity フィールドに参照を保持
private var myDialog: com.google.android.material.bottomsheet.BottomSheetDialog? = null
private var myDialogJob: kotlinx.coroutines.Job? = null

fun showMyDialog() {
    val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(this)
    myDialog = dialog  // 参照保持

    // 既存の collector をキャンセルしてから新規起動
    myDialogJob?.cancel()
    myDialogJob = lifecycleScope.launch {
        someFlow.collectLatest { data ->
            runOnUiThread { /* UI更新 */ }
        }
    }

    // ダイアログ閉じたら collector をキャンセル
    dialog.setOnDismissListener {
        myDialogJob?.cancel()
        myDialogJob = null
        myDialog = null
    }

    dialog.show()
}

override fun onDestroy() {
    myDialog?.dismiss()    // WindowLeaked 防止
    myDialog = null
    myDialogJob?.cancel()
    // ... その他クリーンアップ ...
    (binding.wvBrowser.parent as? android.view.ViewGroup)?.removeView(binding.wvBrowser)
    binding.wvBrowser.destroy()
    super.onDestroy()     // 必ず最後に呼ぶ
}
```

---

## T6: ページロード完了後のJS実行

```kotlin
// ❌ 危険 — loadUrl() は非同期なのでJSが旧ページで動く
loadUrl(targetUrl)
webView.evaluateJavascript("document.querySelector('video').play()") { }

// ✅ 安全 — onPageFinished でキューから取り出して実行
private var pendingDubbingPlayback: (() -> Unit)? = null

// WebViewClient.onPageFinished 内:
override fun onPageFinished(view: WebView?, url: String?) {
    super.onPageFinished(view, url)
    pendingDubbingPlayback?.let { action ->
        pendingDubbingPlayback = null
        view?.postDelayed({ action() }, 1500)  // YT プレイヤー初期化を待つ
    }
}

// 呼び出し側:
if (webView.url != targetUrl) {
    pendingDubbingPlayback = {
        webView.evaluateJavascript("document.querySelector('video').play()") { }
    }
    loadUrl(targetUrl)
} else {
    webView.evaluateJavascript("document.querySelector('video').play()") { }
}
```

---

## T7: SharedPreferences Nullable 対応

```kotlin
// ❌ 危険 — getString() は String? を返す
val modeId = prefs.getString(KEY_MODE, ProcessingMode.DEFAULT.id)
val mode = ProcessingMode.fromId(modeId)  // NullPointerException の可能性

// ✅ 安全
val modeId = prefs.getString(KEY_MODE, ProcessingMode.DEFAULT.id)
    ?: ProcessingMode.DEFAULT.id
val mode = ProcessingMode.fromId(modeId)
```

---

## T8: @Synchronized によるリポジトリ保護

```kotlin
// ❌ 危険 — 並列呼び出しで重複エントリ・状態破壊
fun upsertItem(item: DownloadedVideoItem) {
    val idx = items.indexOfFirst { it.videoId == item.videoId }
    if (idx >= 0) items[idx] = item else items.add(0, item)
    saveItems()
}

// ✅ 安全
@Synchronized
fun upsertItem(item: DownloadedVideoItem) {
    val idx = items.indexOfFirst { it.videoId == item.videoId }
    if (idx >= 0) items[idx] = item else items.add(0, item)
    saveItems()
}

@Synchronized
fun deleteItem(videoId: String) {
    items.removeAll { it.videoId == videoId }
    saveItems()
}
```

---

## T9: HTTP レスポンスコード検証

```kotlin
// ❌ 危険 — 404ページをファイルに書き込む
val connection = URL(url).openConnection() as HttpURLConnection
connection.inputStream.use { input ->
    file.outputStream().use { output -> input.copyTo(output) }
}

// ✅ 安全
val connection = URL(url).openConnection() as HttpURLConnection
if (connection.responseCode !in 200..299) {
    throw IOException("HTTP ${connection.responseCode}: $url")
}
connection.inputStream.use { input ->
    file.outputStream().use { output -> input.copyTo(output) }
}
```

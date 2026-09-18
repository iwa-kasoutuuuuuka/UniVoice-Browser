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

---

## T10: YouTube プレイヤー通信の透過インターセプト (PO Token 対応)

```javascript
// ✅ 安全 — 外部fetchせず、公式プレイヤー自身の正規リクエストからクローン捕捉
const origFetch = window.fetch;
if (typeof origFetch === 'function') {
    window.fetch = function() {
        const args = arguments;
        const url = (typeof args[0] === 'string') ? args[0] : (args[0] && args[0].url ? args[0].url : "");
        const promise = origFetch.apply(this, args);
        if (url && typeof url === 'string' && url.indexOf('timedtext') !== -1) {
            promise.then(function(res) {
                try {
                    const clone = res.clone();
                    clone.text().then(function(txt) {
                        if (txt && txt.length > 30) {
                            bridge.onTimedTextCaptured(url, txt);
                        }
                    });
                } catch(e) {}
            });
        }
        return promise;
    };
}
```

---

## T11: 非言語音響マーカータグの完全除去 (TTS誤読防止)

```kotlin
// ✅ 安全 — [Music], [Applause], ♪ 等を除去し、空になったら発話なしとしてスキップ
fun sanitizeCaption(raw: String): String {
    return raw
        .replace(Regex("\\[?(?:英語|日本語|English|Japanese)?\\s*\\(?(?:自動生成|auto-generated)\\)?\\s*(?:を?クリックして設定)?\\]?", RegexOption.IGNORE_CASE), "")
        .replace(Regex("を?クリックして設定", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\[(?:Music|Applause|Laughter|Snickering|Cheering|Gasp|Sigh|Groan|Chuckle|Cough|Yawn|Throat-clearing|音楽|拍手|笑い|歓声|ため息|せき|くしゃみ|歓声と拍手)[^\\]]*\\]", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\((?:Music|Applause|Laughter|Snickering|Cheering|Gasp|Sigh|Groan|Chuckle|Cough|Yawn|Throat-clearing|音楽|拍手|笑い|歓声|ため息|せき|くしゃみ)[^\\)]*\\)", RegexOption.IGNORE_CASE), "")
        .replace(Regex("[♪♫♬♩#]+"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
}
```

---

## T12: 破損HTMLキャッシュの自動検知・破棄

```kotlin
// ✅ 安全 — ダウンロードされたファイルがHTMLエラーページでないかを検証
fun validateAndDiscardIfCorruptHtml(file: File): Boolean {
    if (!file.exists() || file.length() < 1024) return false
    val header = file.inputStream().use { input ->
        val buf = ByteArray(256)
        val read = input.read(buf)
        if (read > 0) String(buf, 0, read) else ""
    }
    if (header.contains("<!DOCTYPE html", ignoreCase = true) || header.contains("<html", ignoreCase = true)) {
        Log.w(TAG, "破損HTMLファイルを検知したため破棄: ${file.name}")
        file.delete()
        return false
    }
    return true
}
```

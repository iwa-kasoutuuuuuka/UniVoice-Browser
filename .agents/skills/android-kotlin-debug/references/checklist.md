# コードレビュー チェックリスト

新しいコードを書いたとき・機能追加後に必ず確認する。

---

## A. スレッド安全性チェック

### A1. JavaScriptInterface
- [ ] `@JavascriptInterface` メソッド内で `binding.*` を直接触っていないか？
  → `runOnUiThread { }` でラップ必須
- [ ] `@JavascriptInterface` メソッド内で `webView.getUrl()` を呼んでいないか？
  → `Handler(Looper.getMainLooper()).post { }` 経由か、キャッシュ済みURLを返す

### A2. Repository / SharedPreferences
- [ ] `DownloadedVideoRepository` の public メソッドに `@Synchronized` があるか？
- [ ] `SharedPreferences.getString()` の戻り値に `?: defaultValue` が付いているか？
- [ ] `ConcurrentHashMap` のサイズチェック+削除が `synchronized` ブロック内にあるか？

### A3. コレクション
- [ ] `ConcurrentLinkedQueue.size()` をループで呼んでいないか？
  → `AtomicInteger` カウンターで代替

---

## B. Coroutines チェック

### B1. CancellationException
- [ ] `catch (e: Exception)` の先頭に `if (e is CancellationException) throw e` があるか？

### B2. OkHttp
- [ ] コルーチン内で `client.newCall(request).execute()` を直接呼んでいないか？
  → `suspendCancellableCoroutine` + `enqueue()` パターンを使う

### B3. Flow collector
- [ ] `lifecycleScope.launch { flow.collectLatest { } }` をダイアログ/フラグメント内で使っているか？
  → `setOnDismissListener { job.cancel() }` を必ずセット
- [ ] ダイアログを開く関数を複数回呼ぶと collector が積み重なっていないか？
  → `job?.cancel()` してから新しい `job = launch { }` を開始

### B4. 非同期URL遷移
- [ ] `loadUrl()` の直後に `evaluateJavascript()` を呼んでいないか？
  → `pendingAction` キューに入れ `onPageFinished` で実行

---

## C. リソース管理チェック

### C1. MediaPlayer
- [ ] `stop()` と `release()` が独立した `try/catch` に分かれているか？
- [ ] `setOnCompletionListener` 内で `mp.release()` を呼んでいるか？
- [ ] `setOnErrorListener` 内で `mp.release()` + `mediaPlayer = null` をしているか？
- [ ] `isPreparing` フラグで PREPARING 中の `start()` を防いでいるか？

### C2. Dialog / Window
- [ ] `BottomSheetDialog` の参照を Activity フィールドに保持しているか？
- [ ] `onDestroy()` でそのダイアログを `dismiss()` しているか？

### C3. WebView
- [ ] `webView.destroy()` の前に `(parent as? ViewGroup)?.removeView(webView)` を呼んでいるか？

### C4. onDestroy 順序
- [ ] `super.onDestroy()` が `onDestroy()` の**最後**に呼ばれているか？
  （先頭ではなく末尾）

---

## D. データ永続化チェック

### D1. バッチ処理
- [ ] バッチ処理完了後に `translatedSegments` が `segments.json` として保存されているか？
- [ ] `loadFromCacheDirectory()` が `segments.json` を読み込んで正確なタイムスタンプを復元しているか？

### D2. 削除操作
- [ ] キャッシュファイル削除後に `Repository.deleteItem()` も呼ばれているか？
  （ファイル削除と Repository 削除は必ずペア）

### D3. HTTP
- [ ] `HttpURLConnection` で `responseCode in 200..299` のチェックがあるか？
  （404/500 のレスポンスをそのままファイルに書かないようにする）

---

## E. ビルド・型安全性チェック

### E1. Kotlin/Java 境界
- [ ] `ByteArray { リテラル }` のラムダ内でリテラルが `Byte` 型か？（`Int` は型不一致）
  → `0x55.toByte()` または `ByteArray(size)` で初期化

### E2. Android XML
- [ ] `RecyclerView` に `android:maxHeight` を使っていないか？（効果なし）
  → ConstraintLayout の `layout_constraintHeight_max` を使用

### E3. Intent
- [ ] `Intent.ACTION_VIEW` で HTTPS URL を開くとき `Intent.createChooser()` でラップしているか？
  （自アプリへのループバックを防ぐ）

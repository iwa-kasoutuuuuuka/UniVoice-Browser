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

---

## F. モック・ダミーデータ排除チェック（No-Mock-in-Production）

- [ ] 本番コード（`main` ソースセット）に固定ダミー英文（`baseSentences` 等）やサンプル字幕が残っていないか？
- [ ] モデル未配置やリソース未取得時に、ダミー文を捏造せず `IllegalStateException` 等で正直にエラー通知・設定案内を出しているか？
- [ ] 自動生成字幕の場合、ブツ切れ字幕を自然な発話単位にマージし、文末句読点（ピリオド）を適切に復元しているか？

---

## G. 非同期パイプライン・フェーズゲートウェイチェック

- [ ] メディア完全ダウンロード（音声・映像のファイル存在確認＆サイズ整合性チェック）を100%完了してから翻訳フェーズへ移行しているか？
- [ ] 各フェーズ（Phase 1: メディア保存 → Phase 2: 字幕抽出 → Phase 3: 翻訳 → Phase 4: 音声合成）の不変条件（Invariants）が厳格にアサートされているか？
- [ ] 進捗パーセント（全体の `progressPercent` と個別の `audioProgressPercent` / `transProgressPercent` / `videoProgressPercent`）が工程の進行に合わせて正確に更新されているか？

---

## H. WebView / JavaScriptインジェクション堅牢性チェック

- [ ] JS内のすべての `fetch` / `Promise` に `.catch()` があり、例外発生時も Bridge へ通知（空文字列等）を返しているか？（ネイティブ側の8秒タイムアウト待ち防止）
- [ ] YouTube の字幕トラック探索で `languageCode === 'en'` の決め打ちをしていないか？（手動字幕最優先 ＞ 自動生成字幕 ＞ フォールバック）
- [ ] YouTube のSPA画面遷移時やモバイル版でも `player.getPlayerResponse()`, `window.ytInitialPlayerResponse`, `window.ytplayer` から多段フォールバックでトラックを取得できているか？

---

## I. UIモード・画面回転・設定連動チェック

- [ ] 縦画面だけでなく、全画面シアター（Landscape / `CustomView`）時にも再生／一時停止や字幕操作のトリガー（ボタン常設、画面タップ）が失われていないか？
- [ ] 設定画面（⚙️）で発話速度や設定値を変更して戻った際（`onResume`）、バックグラウンドの `BatchDubbingPlayer` 等へ即時同期されているか？
- [ ] `PlaybackParams` の適用時にオーディオ属性を破壊せず、既存の `mediaPlayer.playbackParams` を取得して速度のみ更新しているか？

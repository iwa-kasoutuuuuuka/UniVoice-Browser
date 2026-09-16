# 定期メンテナンス チェックリスト

月1回、または大きな機能追加後に実行する。

---

## 1. ビルド品質確認

```bash
# 警告0件を目標
cmd /c gradlew.bat assembleRelease 2>&1 | findstr /i "warning error"

# Lint チェック
cmd /c gradlew.bat lintRelease
```

---

## 2. 危険コードパターン の grep 検索

新しいバグが混入していないか確認する。

```powershell
# CancellationException を飲み込んでいるか（catch (e: Exception) のみ）
grep -r "catch (e: Exception)" app/src/main/java --include="*.kt" -l

# OkHttp 同期 execute が残っていないか
grep -r "\.execute()" app/src/main/java --include="*.kt" -n

# JavascriptInterface からの UI アクセス（簡易チェック）
grep -r "@JavascriptInterface" app/src/main/java --include="*.kt" -A 10 | grep "binding\."

# super.onDestroy() が最後でないか
grep -n "super.onDestroy" app/src/main/java --include="*.kt" -r
```

---

## 3. リソースリーク確認

```bash
# メモリ使用量（アプリ起動後・長時間使用後に比較）
adb shell dumpsys meminfo com.univoice.browser

# ANR 確認
adb logcat -s "ANR" "*:E"

# MediaPlayer 関連エラー
adb logcat | findstr "MediaPlayer"
```

---

## 4. キャッシュ管理確認

```bash
# キャッシュサイズ確認
adb shell "run-as com.univoice.browser ls -la /data/data/com.univoice.browser/cache/batch_dubbing_cache/"

# 古いキャッシュが自動削除されているか（24時間ルール）
adb logcat | findstr "cleanupExpiredCache"
```

---

## 5. 新規コンポーネント追加時のチェック

新しいクラスや機能を追加したとき、以下を確認する：

### 新しい MediaPlayer を使う場合
- `isPreparing` フラグを持っているか
- `stop()` と `release()` が独立 try/catch か
- `onError` で `release()` しているか

### 新しい `@JavascriptInterface` メソッドを追加する場合
- UIアクセスはすべて `runOnUiThread` か `Handler(Looper.main).post` 経由か
- WebView のメソッドを直接呼んでいないか

### 新しいダイアログを作る場合
- ダイアログの参照を Activity フィールドに保持しているか
- `onDestroy()` で `dismiss()` しているか
- Flow collector を使う場合は `setOnDismissListener` でキャンセルしているか

### 新しいコルーチンを追加する場合
- `catch (e: Exception)` に CancellationException の再スローを追加したか
- OkHttp を使う場合は `suspendCancellableCoroutine` + `enqueue` か
- スコープはライフサイクルに合っているか（`lifecycleScope` vs `applicationScope`）

### 新しい Repository / SharedPreferences の操作を追加する場合
- `@Synchronized` が付いているか
- `getString()` に `?: default` が付いているか

# デバッグ手順 — 5ステップ

GEMINI.md の構造化デバッグプロセスを、このプロジェクト固有の知識で拡張した手順。

---

## Step 1: 事象の明確化

```
□ エラーログの全文をコピー（スタックトレース含む）
□ 「どの操作をしたときに」「何が起きたか」を1文で言語化
□ 再現率を確認（毎回か / たまにか / 特定条件下のみか）
□ 初めて発生したか / 以前は動いていたか
```

**ログ取得コマンド**:
```bash
# エラーのみ
adb logcat -s "UniVoiceBrowser" "*:E"

# 全ログ（フィルタなし）
adb logcat | findstr "UniVoice"

# クラッシュログ
adb logcat -b crash
```

---

## Step 2: クラッシュ種別から原因パターンを絞り込む

| エラーメッセージ | 疑うパターン | 参照 |
|---|---|---|
| `CalledFromWrongThreadException` | パターン1（スレッド違反） | templates.md T1 |
| `IllegalStateException: MediaPlayer called in state...` | パターン3（MediaPlayer状態機械） | templates.md T4 |
| `WindowLeaked` | パターン3（Dialog未dismiss） | templates.md T5 |
| `NullPointerException` in Repository | パターン1（スレッドレース） | templates.md T8 |
| キャンセルしても動き続ける | パターン2（CancellationException飲み込み） | templates.md T2 |
| 再起動後にデータが消える | パターン4（永続化漏れ） | root_causes.md パターン4 |
| ビルドエラー（type mismatch） | パターン5 | root_causes.md パターン5 |
| 再生がズレる・無音 | パターン4（segments.json未保存） | templates.md T6 |
| 映像なし・音声のみ | パターン4（映像URLと音声URLの混同） | root_causes.md パターン4 |
| 動画と全く違う内容が翻訳される | パターン6（ダミー/モック文の残存） | root_causes.md パターン6 |
| メディア破損・ダウンロード未完了で翻訳開始 | パターン7（フェーズゲートウェイ違反） | root_causes.md パターン7 |
| 字幕取得で8秒間フリーズ・タイムアウト | パターン8（JS Promise catch漏れ） | root_causes.md パターン8 |
| 全画面時にボタンが押せない・反応しない | パターン9（UIモード間コントローラ遮断） | root_causes.md パターン9 |

---

## Step 3: 仮説を3つ立てる（必須）

修正コードを書く前に必ず3つの仮説を立てる。
```
仮説1: ___________（最も可能性が高い原因）
仮説2: ___________（代替案・別のコンポーネント）
仮説3: ___________（悪魔の代弁者視点 — 「もし仮説1が間違いなら？」）
```

**検証方法**:
```kotlin
// デバッグログで仮説を検証
Log.d("DEBUG", "スレッド名: ${Thread.currentThread().name}")
Log.d("DEBUG", "MediaPlayer状態: ${mediaPlayer?.isPlaying} / isPreparing: $isPreparing")
Log.d("DEBUG", "segments.json 存在: ${segmentsFile.exists()}")
```

---

## Step 4: 最小修正の適用

- **1つの仮説につき1つの最小変更のみ**加える
- 無関係なリファクタリングを同時に行わない
- 変更前に現在の動作を確認・記録する
- 副作用（他コンポーネントへの影響）をレビューする

**危険な兆候**（修正が大きすぎる場合）:
- 5行以上変更する場合 → もっと小さく分解できないか？
- 別のファイルも変更する場合 → それは本当に必要か？
- インターフェースを変更する場合 → 呼び出し側の影響は？

---

## Step 5: 検証とコミット

### ビルド確認
```bash
cmd /c gradlew.bat assembleRelease
# 警告0件を目標。w: が出た場合は内容を確認
```

### インストールと動作確認
```bash
# エミュレータへインストール
adb install -r app\build\outputs\apk\release\app-release.apk

# スクリーンショット（PowerShell redirection はバイナリ破損するので cmd 経由）
cmd /c "adb exec-out screencap -p > screenshot.png"
```

### 確認項目
```
□ 修正したバグが再現しないか
□ 修正前に動いていた機能が壊れていないか（デグレード確認）
□ ログに新しいエラーや警告が出ていないか
□ 長時間使用してメモリやリソースが増加していないか
```

### コミットとプッシュ
```bash
git add .
git commit -m "fix: [バグ概要] - [修正方針] (BUG-XXX)"
git push origin main
```

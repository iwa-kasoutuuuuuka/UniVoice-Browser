---
name: android-kotlin-debug
description: >-
  UniVoice Browser プロジェクト（Kotlin/Android WebView + Coroutines + TTS）のデバッグ専用スキル。
  スレッド安全性・コルーチンライフサイクル・リソースリーク・データ整合性・ビルドエラーのいずれかで
  バグが疑われるとき、またはユーザーが「デバッグ」「バグ修正」「落ちる」「クラッシュ」と言ったときに使用。
  過去30件のバグ調査から抽出した再現パターン・チェックリスト・修正テンプレートを含む。
---

# UniVoice Browser — Android/Kotlin デバッグスキル

このスキルは、UniVoice Browser で過去に発生した **30件のバグの根本原因分析**から生成された、
再発防止のためのデバッグ手順書です。

詳細は以下の参照ドキュメントに分割されています:
- [根本原因パターン5分類](./references/root_causes.md)
- [デバッグ手順5ステップ](./references/debug_steps.md)
- [コードレビューチェックリスト](./references/checklist.md)
- [頻出修正テンプレート](./references/templates.md)
- [定期メンテナンス](./references/maintenance.md)

---

## ⚡ クイックリファレンス — クラッシュ種別→参照先

| エラーメッセージ | 原因パターン | 参照 |
|---|---|---|
| `CalledFromWrongThreadException` | JavaBridgeスレッドからUI操作 | Templates T1 |
| `IllegalStateException: MediaPlayer` | PREPARING中にstart() / 状態機械違反 | Templates T4 |
| `WindowLeaked` | ダイアログ参照なしでActivity破棄 | Templates T5 |
| コルーチンが停止しない | CancellationException飲み込み | Templates T2 |
| 再起動後データ消失 | 永続化漏れ | Root Causes パターン4 |
| ビルドエラー (type mismatch) | Kotlin型安全性 | Root Causes パターン5 |

---

## ビルド・デプロイ コマンド

```bash
# ビルド
cmd /c gradlew.bat assembleRelease

# インストール（エミュレータ）
adb install -r app\build\outputs\apk\release\app-release.apk

# ログ監視
adb logcat -s "UniVoiceBrowser" "*:E"

# スクリーンショット（PowerShell対応）
cmd /c "adb exec-out screencap -p > screenshot.png"
```

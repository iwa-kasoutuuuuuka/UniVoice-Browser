# 根本原因パターン — 30件バグ分析（2026/09/15）

今回の30件を分析すると、原因は5つのパターンに集約されます。

---

## パターン1: Android スレッドモデルへの無理解（8件 / 最多）

**核心**: `@JavascriptInterface` は **JavaBridge スレッド**で実行される。
WebView のメソッドはすべて **UIスレッド専用**。
SharedPreferences の `edit().apply()` は内部でスレッドを切り替える。

**発生したバグ**:
| バグ | ファイル | 症状 |
|---|---|---|
| C01 | UniVoiceBrowserActivity.kt | `onBatchCaptionsExtractedCallback` からUI操作 → クラッシュ |
| C08 | UniVoiceJSInterface.kt | JavaBridgeスレッドで `WebView.getUrl()` → クラッシュ |
| H04,H05 | DownloadedVideoRepository.kt | 並列書込みで重複エントリ・状態破壊 |
| H09,H10 | UniVoicePipelineManager.kt | ConcurrentLinkedQueue.size() O(N)・非アトミック削除 |

**根本的な問いかけ**:
> このコールバック/ラムダは「何スレッドで」呼ばれるか？

---

## パターン2: Kotlin Coroutines ライフサイクル管理ミス（6件）

**核心**: Coroutines のキャンセル機構は「協調的」。
明示的に CancellationException を再スローし、
suspend 関数内でキャンセルに対応した実装（enqueue等）を使わないと機能しない。

**発生したバグ**:
| バグ | ファイル | 症状 |
|---|---|---|
| C05 | BatchDownloadPipeline.kt | `catch (e: Exception)` が CancellationException を飲み込む |
| H11 | FreeWebTranslationEngine.kt, CloudGeminiTranslationEngine.kt | OkHttp `execute()` がキャンセル不能 |
| H01 | UniVoiceBrowserActivity.kt | ダイアログ閉じてもFlow collector が動き続ける |
| C02 | UniVoiceBrowserActivity.kt | `loadUrl()` 完了前にJS実行（非同期競合） |

**根本的な問いかけ**:
> この `launch {}` はいつキャンセルされるか？ `catch` は CancellationException を再スローしているか？

---

## パターン3: Android リソースの解放漏れ（7件）

**核心**: `MediaPlayer`・`WebView`・`Dialog` はネイティブリソースを保持。
GCだけでは解放されない。同一 try ブロックに複数の解放コードを置くと
途中の例外で後続がスキップされる。

**発生したバグ**:
| バグ | ファイル | 症状 |
|---|---|---|
| C06 | BatchDubbingPlayer.kt | PREPARING中にstart() → IllegalStateException |
| C07 | BatchDubbingPlayer.kt | stop()例外でrelease()スキップ → ネイティブリーク |
| M07 | BatchDubbingPlayer.kt | onError内でrelease()なし |
| H03 | CloudEdgeTtsEngine.kt | completion listener内でrelease()なし |
| H02 | UniVoiceBrowserActivity.kt | BottomSheetDialog参照なし → WindowLeaked |
| M01 | UniVoiceBrowserActivity.kt | super.onDestroy()が先頭・WebView未remove |

**根本的な問いかけ**:
> このリソースは「誰が」「いつ」解放するか？ 例外時でも確実に解放されるか？

---

## パターン4: データ永続化の設計ミス（5件）

**核心**: 機能確認をUI画面上で行うと「画面上は動いている」が、
「再起動後」や「別フローからの参照」で状態が失われていることに気づかない。
削除操作が「ファイル削除」と「メタデータ削除」の両方に作用しないと不整合が生じる。

**発生したバグ**:
| バグ | ファイル | 症状 |
|---|---|---|
| C04 | BatchDownloadPipeline.kt | translatedSegments が RAM のみ→再起動でタイムスタンプ消失 |
| H07 | BatchDubbingPlayer.kt | segments.json なし→偽の4秒間隔で再生 |
| H06 | BatchDownloadPipeline.kt | キャッシュ削除後 Repository 未更新→ゴーストエントリ |
| C03 | BatchDownloadPipeline.kt | 音声URLで映像ファイルを保存→映像なし |

**根本的な問いかけ**:
> このデータは再起動後に正しく復元できるか？ 削除はファイルとメタデータの両方で起きているか？

---

## パターン5: ビルド・型安全性の盲点（4件）

**核心**: Kotlin の型システムは Java由来 API の Nullable を正確に伝えるが、
慣れていないと `getString()` が `String?` を返すことを見落とす。
Android のライフサイクルメソッドには暗黙の呼び出し順序規約がある。

**発生したバグ**:
| バグ | ファイル | 症状 |
|---|---|---|
| C10 | ModelDownloadManager.kt | `ByteArray { 0x55 }` Int/Byte型不一致→コンパイルエラー |
| C09 | UniVoiceConfigManager.kt | `getString()` の `String?` 戻り値を非null扱い |
| M01 | UniVoiceBrowserActivity.kt | `super.onDestroy()` を先頭で呼び以降が無意味に |
| M08 | dialog_downloaded_videos.xml | RecyclerViewに無効な `maxHeight` 属性 |

**根本的な問いかけ**:
> JavaのAPIをKotlinから呼ぶとき、戻り値はNullableか？ ライフサイクルメソッドの正しい呼び出し順序は何か？

---

## 🎯 バグ発生の構造的要因

今回の30件は単なる「うっかりミス」ではなく、以下の構造的な問題から生じています。

### 要因1: 機能追加速度 > 安全性確認速度
機能を次々と追加する中で、各コンポーネントの「安全な使い方」の確認が追いつかなかった。
特に WebView + JavaScriptInterface + Coroutines + MediaPlayer という複数の非同期システムが
絡み合う部分で、それぞれのスレッドモデルを意識せずにコードをつなぎ合わせた。

### 要因2: 「画面上の動作確認」だけでは気づけないバグ
- スレッドバグ: 最初の1-2回は動くが、並行実行時にクラッシュ
- リソースリーク: すぐには問題にならず長時間使用後にクラッシュ
- 永続化バグ: アプリ再起動するまで気づかない

### 要因3: エラーハンドリングによるバグの隠蔽
`catch (e: Exception) { Log.e(...) }` が広く使われており、
本来伝播すべき `CancellationException` や、
本来クラッシュで気づくべきバグが静かに飲み込まれていた。

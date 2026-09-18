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

## パターン6: モック/ダミーデータのプロダクション残存（実データ不整合）（v1.2.1で特定・解消）

**核心**: 開発時・初期実装時に「動作確認用」として置いた固定データ（ダミー英文、サンプル字幕等）が、
フォールバック時や例外分岐に残り続け、本番で動画と無関係な内容が翻訳・発話される。

**発生したバグ**:
| バグ | ファイル | 症状 |
|---|---|---|
| C11 | BatchDownloadPipeline.kt | `baseSentences` 11文がフォールバック時にループ生成され、動画内容と無関係な英語サンプルが翻訳・吹き替えされる |

**根本的な問いかけ**:
> 本番コード（main）にモックや固定サンプル文が残っていないか？ 未対応時はダミーを捏造せず、正直にエラー・設定案内を出しているか？

---

## パターン7: 非同期パイプラインのフェーズ順序破綻（Phase Gateway違反）（v1.2.1で特定・解消）

**核心**: 非同期処理において、「前段タスクの完全な完了（ファイルの存在・サイズ検証）」を確認せずに
後段タスク（翻訳・音声合成）へ移行したため、ファイル未保存や不整合が発生する。

**発生したバグ**:
| バグ | ファイル | 症状 |
|---|---|---|
| C12 | BatchDownloadPipeline.kt | 音声・映像のダウンロード完了を待たずに翻訳が先行開始され、メディアキャッシュが破損 |

**根本的な問いかけ**:
> 前段フェーズの不変条件（ファイルの存在・サイズ検証）が100%完了したことを確認してから後段へ進んでいるか？

---

## パターン8: WebView/JSインジェクションのサイレント破綻（ブラックボックス化）（v1.2.1で特定・解消）

**核心**: WebViewに注入したJavaScriptの非同期処理（Promise/fetch）で `.catch()` が抜けていると、
エラー発生時にネイティブ側へ何の応答も返らず、8秒のタイムアウトまで無駄に待たされる。
また、言語コード（`en`）の決め打ちにより多言語動画で字幕トラック抽出が空振りする。

**発生したバグ**:
| バグ | ファイル | 症状 |
|---|---|---|
| C13 | YouTubeScriptInjector.kt | 字幕取得fetchのPromise reject時にcatchがなく、ネイティブ側が8秒タイムアウト待ちに陥る |
| C14 | YouTubeScriptInjector.kt | `languageCode === 'en'` 固定で他言語の手動字幕や自動生成字幕がスキップされる |

**根本的な問いかけ**:
> JS側のすべての非同期処理に `.catch()` があるか？ ネイティブへの通知（空通知含む）が100%保証されているか？

---

## パターン9: UI表示モード切り替え時のコントローラ可用性遮断（v1.2.0で特定・解消）

**核心**: 縦画面から全画面（Landscape / CustomView）に切り替えた際、トップバーやナビゲーションが隠蔽され、
一時停止や字幕操作のUIイベントがユーザーから届かなくなる。

**発生したバグ**:
| バグ | ファイル | 症状 |
|---|---|---|
| C15 | UniVoiceBrowserActivity.kt | 全画面シアター時に一時停止ボタンが押せない、画面タップリスナーが欠落 |

---

## パターン10: YouTube最新セキュリティ・PO Tokenボット検知遮断（v1.2.3で特定・解消）

**核心**: YouTubeはProof-of-Origin (PO) Token（`exp=xpe` 等）によるボット検知を強化しており、
外部スクリプト（`fetch(timedtext)`）からの直接字幕リクエストは0バイト返却や403で遮断される。
公式プレイヤー自身が行う通信からインターセプトするか、能動的に公式プレイヤーの字幕モジュールを叩かせる必要がある。

**発生したバグ**:
| バグ | ファイル | 症状 |
|---|---|---|
| C16 | YouTubeScriptInjector.kt, UniVoiceJSInterface.kt | ユーザーが「吹き替え開始」を押しても字幕URLが0バイトになり、「字幕トラックが見つかりません」と失敗する |

**根本的な問いかけ**:
> 外部から独立フェッチしようとしていないか？ 公式プレイヤーの内部ネットワーク通信（`fetch`/`XHR`）から透過フックしているか？

---

## パターン11: 非言語音響マーカー（[Music], [Applause]）の誤読・TTS汚染（v1.2.4で特定・解消）

**核心**: YouTube自動生成字幕（ASR）には、BGMや環境音に対して `[Music]`, `[Applause]`, `[Laughter]`, `♪` 等の音響タグが記録される。
これをそのまま翻訳エンジンに渡すと「[拍手] [音楽]。」と直訳され、TTSが動画中延々と読み上げ続ける。
また、音響タグを除去した結果、実テキストが空になる「無言・タイムラプス・BGM動画」に対するフォールバック処理が必要。

**発生したバグ**:
| バグ | ファイル | 症状 |
|---|---|---|
| C17 | YouTubeTimedTextParser.kt, YouTubeScriptInjector.kt | タイムラプス動画等で「拍手」「音楽」しか読まなくなる |

**根本的な問いかけ**:
> 字幕サニタイズで音響効果タグ（`[Music]`, `[Applause]`, `♪` 等）を除去しているか？ 除去後に空になったセグメントを発話なしとして正しくスキップしているか？

---

## パターン12: 破損HTMLキャッシュによるオフライン音声解析クラッシュ（v1.2.2で特定・解消）

**核心**: 音声ダウンロード時にネットワーク切断やYouTubeの仕様変更でエラーページ（HTML）が返された場合、
その内容を検証せずにファイルキャッシュとして保存してしまうと、次回以降にWhisperやデコーダへ渡った際にネイティブクラッシュを引き起こす。

**発生したバグ**:
| バグ | ファイル | 症状 |
|---|---|---|
| C18 | BatchDownloadPipeline.kt | ダウンロードされた数KBのHTMLエラーファイルを音声ファイルとして扱い、オフライン再生・文字起こしでクラッシュ |

**根本的な問いかけ**:
> 保存されたメディアファイルの先頭バイト（Magic Number）やサイズ（HTMLタグ検知等）を検証しているか？ 不正ファイルは即座に破棄しているか？

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

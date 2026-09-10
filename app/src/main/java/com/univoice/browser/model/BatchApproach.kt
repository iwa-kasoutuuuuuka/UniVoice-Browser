package com.univoice.browser.model

/**
 * 視聴スタイルの実行区分（従来ストリーミング vs 事前ダウンロード徹底翻訳）
 */
enum class ExecutionStyle(
    val id: String,
    val titleJapanese: String,
    val descriptionJapanese: String
) {
    /** 従来型：即座に再生し、字幕を逐次ストリーミング翻訳 */
    STREAMING(
        id = "streaming",
        titleJapanese = "リアルタイム・ストリーミング",
        descriptionJapanese = "待ち時間ゼロで即座に動画を再生し、細切れ字幕を逐次翻訳・読み上げ（従来モード）"
    ),

    /** 新機能：動画/音声を事前取得し、じっくり強力AIで全編一括翻訳＆尺合わせ吹き替え */
    BATCH_DOWNLOAD(
        id = "batch_download",
        titleJapanese = "ダウンロード徹底バッチ翻訳",
        descriptionJapanese = "動画を事前に取得し、強力な言語モデルで全編の文脈把握・文字数尺合わせ翻訳・高品質吹き替えを実施"
    );

    companion object {
        fun fromId(id: String?): ExecutionStyle {
            return values().firstOrNull { it.id.equals(id, ignoreCase = true) } ?: STREAMING
        }
    }
}

/**
 * 事前ダウンロード徹底バッチ翻訳における3大アプローチ
 */
enum class BatchApproach(
    val id: String,
    val titleJapanese: String,
    val descriptionJapanese: String
) {
    /** アプローチA: 完全端末内完結（オンデバイス型） */
    APPROACH_A_LOCAL(
        id = "approach_a_local",
        titleJapanese = "アプローチA: 完全端末内完結",
        descriptionJapanese = "端末内AI（Whisper/Edge LLM/VOICEVOX ONNX）のみで処理。通信量・API利用料0円で完全オフライン動作"
    ),

    /** アプローチB: クラウドAI連携型（Gemini/OpenAI API等利用） */
    APPROACH_B_CLOUD(
        id = "approach_b_cloud",
        titleJapanese = "アプローチB: クラウドAI連携型",
        descriptionJapanese = "長文文脈理解に長けたGemini 1.5 Pro等の大型LLMと高品質クラウドTTSを連携。最高峰の翻訳品質と自然な吹き替え"
    ),

    /** アプローチC: ハイブリッド型（推奨・高効率） */
    APPROACH_C_HYBRID(
        id = "approach_c_hybrid",
        titleJapanese = "アプローチC: ハイブリッド型（推奨）",
        descriptionJapanese = "公式/自動字幕がある場合は即座にテキスト抽出、無い場合のみ音声文字起こしを実施。クラウドLLMで一括翻訳し高速＆高品質を両立"
    );

    companion object {
        fun fromId(id: String?): BatchApproach {
            return values().firstOrNull { it.id.equals(id, ignoreCase = true) } ?: APPROACH_C_HYBRID
        }
    }
}

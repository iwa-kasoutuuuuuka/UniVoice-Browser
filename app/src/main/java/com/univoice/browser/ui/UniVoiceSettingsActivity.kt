package com.univoice.browser.ui

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.univoice.browser.R
import com.univoice.browser.batch.BatchDownloadPipeline
import com.univoice.browser.config.UniVoiceConfigManager
import com.univoice.browser.databinding.ActivityUnivoiceSettingsBinding
import com.univoice.browser.model.BatchApproach
import com.univoice.browser.model.ExecutionStyle
import com.univoice.browser.model.ProcessingMode
import com.univoice.browser.model.TranslationEngineType
import com.univoice.browser.model.TtsEngineType
import com.univoice.browser.model.UniVoiceSettings
import kotlinx.coroutines.launch

/**
 * UniVoice Browser の動作設定画面
 * 視聴スタイル切替（ストリーミング vs バッチ翻訳）、バッチアプローチ（A/B/C）、
 * 4つの処理モード選択および手動カスタマイズ、キャッシュ管理を提供
 */
class UniVoiceSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityUnivoiceSettingsBinding
    private lateinit var configManager: UniVoiceConfigManager

    override fun attachBaseContext(newBase: android.content.Context) {
        val locale = java.util.Locale.JAPANESE
        java.util.Locale.setDefault(locale)
        val config = android.content.res.Configuration(newBase.resources.configuration)
        config.setLocale(locale)
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUnivoiceSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        configManager = UniVoiceConfigManager.getInstance(this)

        setupToolbar()
        setupSpinners()
        setupExecutionStyleListeners()
        setupModeSelectionListeners()
        setupActionButtons()
        loadCurrentSettingsIntoUi()
    }

    private fun setupToolbar() {
        binding.toolbarSettings.setNavigationOnClickListener {
            finish()
        }
    }

    private fun setupSpinners() {
        val transEngineItems = TranslationEngineType.values().map { it.titleJapanese }
        val transAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, transEngineItems)
        binding.spinnerTranslationEngine.adapter = transAdapter

        val ttsEngineItems = TtsEngineType.values().map { it.titleJapanese }
        val ttsAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, ttsEngineItems)
        binding.spinnerTtsEngine.adapter = ttsAdapter
    }

    /**
     * 視聴スタイル（リアルタイム vs バッチ翻訳）リスナー
     */
    private fun setupExecutionStyleListeners() {
        binding.rgExecutionStyle.setOnCheckedChangeListener { _, _ ->
            updateVisibleSections()
        }
    }

    /**
     * 動作モード切り替えイベントと手動詳細エリア等の動的表示制御
     */
    private fun setupModeSelectionListeners() {
        binding.rgProcessingModes.setOnCheckedChangeListener { _, _ ->
            updateVisibleSections()
        }
        binding.rgBatchApproaches.setOnCheckedChangeListener { _, _ ->
            updateVisibleSections()
        }

        binding.sliderTtsSpeed.addOnChangeListener { _, value, _ ->
            binding.tvTtsSpeedLabel.text = getString(R.string.label_tts_speed, value)
        }

        // 速度・音声試聴テスト
        binding.btnTestSpeechSpeed.setOnClickListener {
            val speed = binding.sliderTtsSpeed.value
            val selectedGender = if (binding.rbVoiceMale.isChecked) {
                com.univoice.browser.model.VoiceGender.MALE
            } else {
                com.univoice.browser.model.VoiceGender.FEMALE
            }
            lifecycleScope.launch {
                binding.btnTestSpeechSpeed.isEnabled = false
                try {
                    val tts = com.univoice.browser.tts.CloudEdgeTtsEngine(this@UniVoiceSettingsActivity, selectedGender)
                    tts.initialize()
                    val sampleText = if (selectedGender == com.univoice.browser.model.VoiceGender.MALE) {
                        "UniVoice Browserです。男性音声で動画を日本語吹き替えします。現在の発話速度は${String.format(java.util.Locale.JAPAN, "%.1f", speed)}倍です。"
                    } else {
                        "UniVoice Browserです。女性音声で動画を日本語吹き替えします。現在の発話速度は${String.format(java.util.Locale.JAPAN, "%.1f", speed)}倍です。"
                    }
                    tts.synthesizeAndPlay(sampleText, speed = speed)
                } catch (_: Exception) {} finally {
                    binding.btnTestSpeechSpeed.isEnabled = true
                }
            }
        }
    }

    /**
     * 選択中の視聴スタイルと処理モードに応じて、関連する設定項目のみを表示
     */
    private fun updateVisibleSections() {
        val isBatch = binding.rbStyleBatch.isChecked

        if (isBatch) {
            // バッチ翻訳選択時: ストリーミング設定群を非表示、バッチ設定群を表示
            binding.layoutStreamingContainer.visibility = View.GONE
            binding.layoutBatchContainer.visibility = View.VISIBLE
            binding.layoutBackgroundPlaybackOption.visibility = View.GONE
            binding.dividerBackgroundPlayback.visibility = View.GONE

            // アプローチB (クラウドAI) または アプローチC (ハイブリッド) の場合にGemini APIキーを表示
            val isApproachA = binding.rbApproachA.isChecked
            binding.layoutGeminiApiKeySection.visibility = if (isApproachA) View.GONE else View.VISIBLE
        } else {
            // ストリーミング選択時: バッチ設定群を非表示、ストリーミング設定群を表示
            binding.layoutStreamingContainer.visibility = View.VISIBLE
            binding.layoutBatchContainer.visibility = View.GONE
            binding.layoutBackgroundPlaybackOption.visibility = View.VISIBLE
            binding.dividerBackgroundPlayback.visibility = View.VISIBLE

            // モードに応じた制御 (完全ローカル、完全API、最適構成、手動)
            val isPureLocal = binding.rbPureLocal.isChecked
            val isManual = binding.rbManual.isChecked

            binding.layoutManualSettings.visibility = if (isManual) View.VISIBLE else View.GONE
            binding.layoutGeminiApiKeySection.visibility = if (isPureLocal) View.GONE else View.VISIBLE
        }

        // オンデバイスAIモデル管理コンテナの表示制御
        // バッチ翻訳時（Whisperや完全ローカル用）、またはストリーミングの完全ローカル/手動設定時に表示
        val showLocalModels = if (isBatch) true else (binding.rbPureLocal.isChecked || binding.rbManual.isChecked)
        binding.layoutLocalModelsContainer.visibility = if (showLocalModels) View.VISIBLE else View.GONE
    }

    /**
     * 保存およびリセットボタン、キャッシュクリア等のリスナー設定
     */
    private fun setupActionButtons() {
        binding.btnSaveSettings.setOnClickListener {
            saveSettingsFromUi()
        }

        binding.btnResetDefaults.setOnClickListener {
            configManager.resetToRecommendedDefaults()
            loadCurrentSettingsIntoUi()
            Toast.makeText(this, "推奨デフォルト（最適構成）に復元しました", Toast.LENGTH_SHORT).show()
        }

        // 手動キャッシュ全削除
        binding.btnClearBatchCache.setOnClickListener {
            val pipeline = BatchDownloadPipeline(
                context = this,
                geminiApiKey = configManager.currentSettings.geminiApiKey,
                batchApproach = configManager.currentSettings.batchApproach
            )
            pipeline.clearAllBatchCache()
            Toast.makeText(this, "バッチ翻訳・吹き替えキャッシュを完全に消去しました", Toast.LENGTH_SHORT).show()
        }

        // 無料Gemini APIキー取得リンク
        binding.btnGetApiKey.setOnClickListener {
            try {
                val intent = android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://aistudio.google.com/app/apikey")
                )
                startActivity(android.content.Intent.createChooser(intent, "ブラウザを選択"))
            } catch (e: Exception) {
                Toast.makeText(this, "ブラウザの起動に失敗しました: https://aistudio.google.com/app/apikey", Toast.LENGTH_LONG).show()
            }
        }

        // 日英対訳スクリプト画面へ遷移
        binding.btnOpenTranscripts.setOnClickListener {
            startActivity(android.content.Intent(this, UniVoiceTranscriptActivity::class.java))
        }

        // オンデバイスAIモデルのダウンロード・配備
        binding.btnDownloadModels.setOnClickListener {
            startModelDeployment()
        }

        refreshModelStatus()
    }

    private fun refreshModelStatus() {
        val modelMgr = com.univoice.browser.modelmgr.ModelDownloadManager.getInstance(this)
        val gemmaInstalled = modelMgr.isModelInstalled(com.univoice.browser.modelmgr.ModelDownloadManager.MODEL_GEMMA)
        val gemmaSize = modelMgr.getModelSizeFormatted(com.univoice.browser.modelmgr.ModelDownloadManager.MODEL_GEMMA)
        binding.tvGemmaStatus.text = if (gemmaInstalled) {
            "Gemma 2B LLM: 配備完了 ($gemmaSize - Snapdragon NPU加速準備完了)"
        } else {
            "Gemma 2B LLM: 未配備 (完全ローカル時にオフライン辞書で代替)"
        }
        binding.tvGemmaStatus.setTextColor(
            if (gemmaInstalled) android.graphics.Color.parseColor("#2E7D32") else android.graphics.Color.parseColor("#C62828")
        )

        val voicevoxInstalled = modelMgr.isModelInstalled(com.univoice.browser.modelmgr.ModelDownloadManager.MODEL_VOICEVOX)
        val voicevoxSize = modelMgr.getModelSizeFormatted(com.univoice.browser.modelmgr.ModelDownloadManager.MODEL_VOICEVOX)
        binding.tvVoicevoxStatus.text = if (voicevoxInstalled) {
            "VOICEVOX ONNX: 配備完了 ($voicevoxSize - NNAPI準備完了)"
        } else {
            "VOICEVOX ONNX: 未配備 (標準TTSで代替)"
        }
        binding.tvVoicevoxStatus.setTextColor(
            if (voicevoxInstalled) android.graphics.Color.parseColor("#2E7D32") else android.graphics.Color.parseColor("#C62828")
        )

        val whisperInstalled = modelMgr.isModelInstalled(com.univoice.browser.modelmgr.ModelDownloadManager.MODEL_WHISPER)
        val whisperSize = modelMgr.getModelSizeFormatted(com.univoice.browser.modelmgr.ModelDownloadManager.MODEL_WHISPER)
        binding.tvWhisperStatus.text = if (whisperInstalled) {
            "Whisper 音声文字起こし: 配備完了 ($whisperSize - バッチ文字起こし準備完了)"
        } else {
            "Whisper 音声文字起こし: 未配備 (バッチ音声認識時に必要)"
        }
        binding.tvWhisperStatus.setTextColor(
            if (whisperInstalled) android.graphics.Color.parseColor("#2E7D32") else android.graphics.Color.parseColor("#C62828")
        )
    }

    private fun startModelDeployment() {
        val modelMgr = com.univoice.browser.modelmgr.ModelDownloadManager.getInstance(this)
        binding.pbModelDownload.visibility = View.VISIBLE
        binding.btnDownloadModels.isEnabled = false
        binding.btnDownloadModels.text = "モデルを配備中..."

        lifecycleScope.launch {
            modelMgr.downloadOrInstallModel(com.univoice.browser.modelmgr.ModelDownloadManager.MODEL_GEMMA)
            modelMgr.downloadOrInstallModel(com.univoice.browser.modelmgr.ModelDownloadManager.MODEL_VOICEVOX)
            modelMgr.downloadOrInstallModel(com.univoice.browser.modelmgr.ModelDownloadManager.MODEL_WHISPER)
            binding.pbModelDownload.visibility = View.GONE
            binding.btnDownloadModels.isEnabled = true
            binding.btnDownloadModels.text = "ローカルAIモデルを再配備 / 更新"
            refreshModelStatus()
            Toast.makeText(this@UniVoiceSettingsActivity, "ローカルAIモデル（Gemma/VOICEVOX/Whisper）の配備が完了しました", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 現在の設定値をUIコンポーネントに反映
     */
    private fun loadCurrentSettingsIntoUi() {
        val settings = configManager.currentSettings

        // 視聴スタイル
        if (settings.executionStyle == ExecutionStyle.BATCH_DOWNLOAD) {
            binding.rbStyleBatch.isChecked = true
        } else {
            binding.rbStyleStreaming.isChecked = true
        }

        // バッチアプローチ
        when (settings.batchApproach) {
            BatchApproach.APPROACH_A_LOCAL -> binding.rbApproachA.isChecked = true
            BatchApproach.APPROACH_B_CLOUD -> binding.rbApproachB.isChecked = true
            BatchApproach.APPROACH_C_HYBRID -> binding.rbApproachC.isChecked = true
        }

        // 動作モード
        when (settings.currentMode) {
            ProcessingMode.PURE_LOCAL -> binding.rbPureLocal.isChecked = true
            ProcessingMode.PURE_API -> binding.rbPureApi.isChecked = true
            ProcessingMode.HYBRID_OPTIMAL -> binding.rbHybridOptimal.isChecked = true
            ProcessingMode.MANUAL -> binding.rbManual.isChecked = true
        }

        // 表示状態を一括更新
        updateVisibleSections()

        // 手動設定項目
        val transIndex = TranslationEngineType.values().indexOf(settings.manualTranslationEngine)
        if (transIndex >= 0) binding.spinnerTranslationEngine.setSelection(transIndex)

        val ttsIndex = TtsEngineType.values().indexOf(settings.manualTtsEngine)
        if (ttsIndex >= 0) binding.spinnerTtsEngine.setSelection(ttsIndex)

        // 音声性別
        if (settings.voiceGender == com.univoice.browser.model.VoiceGender.MALE) {
            binding.rbVoiceMale.isChecked = true
        } else {
            binding.rbVoiceFemale.isChecked = true
        }

        binding.etGeminiApiKey.setText(settings.geminiApiKey)

        // ハードウェア・動作制御
        binding.switchHardwareAccel.isChecked = settings.hardwareAcceleration
        binding.switchAudioSuppression.isChecked = settings.audioSuppressionEnabled
        binding.switchBackgroundPlayback.isChecked = settings.backgroundPlaybackEnabled
        binding.switchAdBlock.isChecked = settings.adBlockEnabled
        binding.sliderTtsSpeed.value = settings.speechSpeed.coerceIn(0.5f, 2.5f)
        binding.tvTtsSpeedLabel.text = getString(R.string.label_tts_speed, settings.speechSpeed)
    }

    /**
     * UIの入力値を取得し保存
     */
    private fun saveSettingsFromUi() {
        val selectedExecutionStyle = if (binding.rbStyleBatch.isChecked) {
            ExecutionStyle.BATCH_DOWNLOAD
        } else {
            ExecutionStyle.STREAMING
        }

        val selectedBatchApproach = when (binding.rgBatchApproaches.checkedRadioButtonId) {
            R.id.rbApproachA -> BatchApproach.APPROACH_A_LOCAL
            R.id.rbApproachB -> BatchApproach.APPROACH_B_CLOUD
            R.id.rbApproachC -> BatchApproach.APPROACH_C_HYBRID
            else -> BatchApproach.APPROACH_C_HYBRID
        }

        val selectedMode = when (binding.rgProcessingModes.checkedRadioButtonId) {
            R.id.rbPureLocal -> ProcessingMode.PURE_LOCAL
            R.id.rbPureApi -> ProcessingMode.PURE_API
            R.id.rbHybridOptimal -> ProcessingMode.HYBRID_OPTIMAL
            R.id.rbManual -> ProcessingMode.MANUAL
            else -> ProcessingMode.HYBRID_OPTIMAL
        }

        val selectedTransEngine = TranslationEngineType.values().getOrElse(
            binding.spinnerTranslationEngine.selectedItemPosition
        ) { TranslationEngineType.GEMINI_CLOUD }

        val selectedTtsEngine = TtsEngineType.values().getOrElse(
            binding.spinnerTtsEngine.selectedItemPosition
        ) { TtsEngineType.LOCAL_VOICEVOX_ONNX }

        val selectedVoiceGender = if (binding.rbVoiceMale.isChecked) {
            com.univoice.browser.model.VoiceGender.MALE
        } else {
            com.univoice.browser.model.VoiceGender.FEMALE
        }

        val rawApiKey = binding.etGeminiApiKey.text?.toString()?.trim() ?: ""
        // アプローチA（完全ローカル）選択時やAPI不要モードではAPIキーが不要なためサニタイズ
        val apiKey = if (selectedExecutionStyle == ExecutionStyle.BATCH_DOWNLOAD && selectedBatchApproach == BatchApproach.APPROACH_A_LOCAL) {
            ""
        } else {
            rawApiKey
        }
        val hwAccel = binding.switchHardwareAccel.isChecked
        val audioSuppression = binding.switchAudioSuppression.isChecked
        val backgroundPlayback = binding.switchBackgroundPlayback.isChecked
        val adBlock = binding.switchAdBlock.isChecked
        val speed = binding.sliderTtsSpeed.value

        val newSettings = configManager.currentSettings.copy(
            executionStyle = selectedExecutionStyle,
            batchApproach = selectedBatchApproach,
            currentMode = selectedMode,
            manualTranslationEngine = selectedTransEngine,
            manualTtsEngine = selectedTtsEngine,
            geminiApiKey = apiKey,
            hardwareAcceleration = hwAccel,
            audioSuppressionEnabled = audioSuppression,
            backgroundPlaybackEnabled = backgroundPlayback,
            adBlockEnabled = adBlock,
            speechSpeed = speed,
            voiceGender = selectedVoiceGender
        )

        configManager.updateSettings(newSettings)
        Toast.makeText(this, getString(R.string.msg_saved), Toast.LENGTH_SHORT).show()
        finish()
    }
}

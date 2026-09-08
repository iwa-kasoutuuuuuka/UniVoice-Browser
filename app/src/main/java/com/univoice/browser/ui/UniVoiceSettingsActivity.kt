package com.univoice.browser.ui

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.univoice.browser.R
import com.univoice.browser.config.UniVoiceConfigManager
import com.univoice.browser.databinding.ActivityUnivoiceSettingsBinding
import com.univoice.browser.model.ProcessingMode
import com.univoice.browser.model.TranslationEngineType
import com.univoice.browser.model.TtsEngineType
import com.univoice.browser.model.UniVoiceSettings
import kotlinx.coroutines.launch

/**
 * UniVoice Browser の動作設定画面
 * 4つの処理モード選択および手動カスタマイズを提供
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
        setupModeSelectionListeners()
        setupActionButtons()
        loadCurrentSettingsIntoUi()
    }

    private fun setupToolbar() {
        binding.toolbarSettings.setNavigationOnClickListener {
            finish()
        }
    }

    /**
     * 手動モード用のドロップダウンアダプター初期化
     */
    private fun setupSpinners() {
        // 翻訳エンジンのリスト
        val transEngineItems = TranslationEngineType.values().map { it.titleJapanese }
        val transAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, transEngineItems)
        binding.spinnerTranslationEngine.adapter = transAdapter

        // 音声合成エンジンのリスト
        val ttsEngineItems = TtsEngineType.values().map { it.titleJapanese }
        val ttsAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, ttsEngineItems)
        binding.spinnerTtsEngine.adapter = ttsAdapter
    }

    /**
     * 4つのモード切り替えイベントと手動詳細エリアの動的表示制御
     */
    private fun setupModeSelectionListeners() {
        binding.rgProcessingModes.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.rbPureLocal -> {
                    binding.layoutManualSettings.visibility = View.GONE
                    binding.cardGeminiApiKey.visibility = View.GONE
                }
                R.id.rbPureApi, R.id.rbHybridOptimal -> {
                    binding.layoutManualSettings.visibility = View.GONE
                    binding.cardGeminiApiKey.visibility = View.VISIBLE
                }
                R.id.rbManual -> {
                    // 手動設定時のみサブオプションを展開
                    binding.layoutManualSettings.visibility = View.VISIBLE
                    binding.cardGeminiApiKey.visibility = View.VISIBLE
                }
            }
        }

        binding.sliderTtsSpeed.addOnChangeListener { _, value, _ ->
            binding.tvTtsSpeedLabel.text = getString(R.string.label_tts_speed, value)
        }

        // 速度試聴テスト
        binding.btnTestSpeechSpeed.setOnClickListener {
            val speed = binding.sliderTtsSpeed.value
            lifecycleScope.launch {
                binding.btnTestSpeechSpeed.isEnabled = false
                try {
                    val tts = com.univoice.browser.tts.AndroidSystemTtsEngine(this@UniVoiceSettingsActivity)
                    tts.initialize()
                    tts.synthesizeAndPlay("UniVoice Browserです。現在の発話速度は${String.format("%.1f", speed)}倍です。", speed = speed)
                } catch (_: Exception) {} finally {
                    binding.btnTestSpeechSpeed.isEnabled = true
                }
            }
        }
    }

    /**
     * 保存およびリセットボタン、追加機能ボタンのリスナー設定
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

        // 無料Gemini APIキー取得リンク
        binding.btnGetApiKey.setOnClickListener {
            try {
                val intent = android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://aistudio.google.com/app/apikey")
                )
                startActivity(intent)
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
    }

    private fun startModelDeployment() {
        val modelMgr = com.univoice.browser.modelmgr.ModelDownloadManager.getInstance(this)
        binding.pbModelDownload.visibility = View.VISIBLE
        binding.btnDownloadModels.isEnabled = false
        binding.btnDownloadModels.text = "モデルを配備中..."

        lifecycleScope.launch {
            modelMgr.downloadOrInstallModel(com.univoice.browser.modelmgr.ModelDownloadManager.MODEL_GEMMA)
            modelMgr.downloadOrInstallModel(com.univoice.browser.modelmgr.ModelDownloadManager.MODEL_VOICEVOX)
            binding.pbModelDownload.visibility = View.GONE
            binding.btnDownloadModels.isEnabled = true
            binding.btnDownloadModels.text = "ローカルAIモデルを再配備 / 更新"
            refreshModelStatus()
            Toast.makeText(this@UniVoiceSettingsActivity, "ローカルAIモデルの配備が完了しました", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 現在の設定値をUIコンポーネントに反映
     */
    private fun loadCurrentSettingsIntoUi() {
        val settings = configManager.currentSettings

        // 動作モード
        when (settings.currentMode) {
            ProcessingMode.PURE_LOCAL -> {
                binding.rbPureLocal.isChecked = true
                binding.cardGeminiApiKey.visibility = View.GONE
                binding.layoutManualSettings.visibility = View.GONE
            }
            ProcessingMode.PURE_API -> {
                binding.rbPureApi.isChecked = true
                binding.cardGeminiApiKey.visibility = View.VISIBLE
                binding.layoutManualSettings.visibility = View.GONE
            }
            ProcessingMode.HYBRID_OPTIMAL -> {
                binding.rbHybridOptimal.isChecked = true
                binding.cardGeminiApiKey.visibility = View.VISIBLE
                binding.layoutManualSettings.visibility = View.GONE
            }
            ProcessingMode.MANUAL -> {
                binding.rbManual.isChecked = true
                binding.cardGeminiApiKey.visibility = View.VISIBLE
                binding.layoutManualSettings.visibility = View.VISIBLE
            }
        }

        // 手動設定項目
        val transIndex = TranslationEngineType.values().indexOf(settings.manualTranslationEngine)
        if (transIndex >= 0) binding.spinnerTranslationEngine.setSelection(transIndex)

        val ttsIndex = TtsEngineType.values().indexOf(settings.manualTtsEngine)
        if (ttsIndex >= 0) binding.spinnerTtsEngine.setSelection(ttsIndex)

        binding.etGeminiApiKey.setText(settings.geminiApiKey)

        // ハードウェア・動作制御
        binding.switchHardwareAccel.isChecked = settings.hardwareAcceleration
        binding.switchAudioSuppression.isChecked = settings.audioSuppressionEnabled
        binding.switchAdBlock.isChecked = settings.adBlockEnabled
        binding.sliderTtsSpeed.value = settings.speechSpeed.coerceIn(0.5f, 2.5f)
        binding.tvTtsSpeedLabel.text = getString(R.string.label_tts_speed, settings.speechSpeed)
    }

    /**
     * UIの入力値を取得し保存
     */
    private fun saveSettingsFromUi() {
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

        val apiKey = binding.etGeminiApiKey.text?.toString()?.trim() ?: ""
        val hwAccel = binding.switchHardwareAccel.isChecked
        val audioSuppression = binding.switchAudioSuppression.isChecked
        val adBlock = binding.switchAdBlock.isChecked
        val speed = binding.sliderTtsSpeed.value

        val newSettings = configManager.currentSettings.copy(
            currentMode = selectedMode,
            manualTranslationEngine = selectedTransEngine,
            manualTtsEngine = selectedTtsEngine,
            geminiApiKey = apiKey,
            hardwareAcceleration = hwAccel,
            audioSuppressionEnabled = audioSuppression,
            adBlockEnabled = adBlock,
            speechSpeed = speed
        )

        configManager.updateSettings(newSettings)
        Toast.makeText(this, getString(R.string.msg_saved), Toast.LENGTH_SHORT).show()
        finish()
    }
}

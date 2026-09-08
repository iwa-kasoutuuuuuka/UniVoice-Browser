package com.univoice.browser.hardware

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Snapdragon 8 Gen 2 / Poco F6 Pro のサーマルマネジメント（発熱監視）マネージャー
 * 端末が高熱に達した際にローカルAI推論負荷を自動抑制し、クラウドへ一時オフロードする自律制御を提供
 */
class UniVoiceThermalManager(context: Context) {

    companion object {
        private const val TAG = "UniVoiceThermalManager"
    }

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    private val _isThrottlingRequired = MutableStateFlow(false)
    val isThrottlingRequired: StateFlow<Boolean> = _isThrottlingRequired.asStateFlow()

    private val _thermalWarningMessage = MutableStateFlow<String?>(null)
    val thermalWarningMessage: StateFlow<String?> = _thermalWarningMessage.asStateFlow()

    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null

    init {
        registerThermalListener()
    }

    private fun registerThermalListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && powerManager != null) {
            thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
                handleThermalStatusChange(status)
            }
            try {
                powerManager.addThermalStatusListener(thermalListener!!)
                Log.i(TAG, "[UniVoiceBrowser] Android サーマルステータス監視を開始しました")
            } catch (e: Exception) {
                Log.w(TAG, "[UniVoiceBrowser] サーマルリスナー登録警告: ${e.message}")
            }
        }
    }

    private fun handleThermalStatusChange(status: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            when (status) {
                PowerManager.THERMAL_STATUS_NONE,
                PowerManager.THERMAL_STATUS_LIGHT -> {
                    _isThrottlingRequired.value = false
                    _thermalWarningMessage.value = null
                }
                PowerManager.THERMAL_STATUS_MODERATE -> {
                    Log.i(TAG, "[UniVoiceBrowser] 端末温度注意: 中程度の温度上昇を検知")
                    _isThrottlingRequired.value = false
                }
                PowerManager.THERMAL_STATUS_SEVERE,
                PowerManager.THERMAL_STATUS_CRITICAL,
                PowerManager.THERMAL_STATUS_EMERGENCY,
                PowerManager.THERMAL_STATUS_SHUTDOWN -> {
                    Log.w(TAG, "[UniVoiceBrowser] 端末が高温状態です (ステータス: $status)。NPU/GPU保護のためクラウド処理へ一時退避します")
                    _isThrottlingRequired.value = true
                    _thermalWarningMessage.value = "端末温度上昇を検知: サーマル保護のためクラウド処理へ一時移行しました"
                }
            }
        }
    }

    fun release() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && powerManager != null && thermalListener != null) {
            try {
                powerManager.removeThermalStatusListener(thermalListener!!)
                thermalListener = null
            } catch (e: Exception) {
                Log.w(TAG, "[UniVoiceBrowser] サーマルリスナー解除警告: ${e.message}")
            }
        }
    }
}

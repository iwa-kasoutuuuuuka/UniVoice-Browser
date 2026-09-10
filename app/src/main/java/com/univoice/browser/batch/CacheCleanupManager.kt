package com.univoice.browser.batch

import android.content.Context
import android.util.Log
import com.univoice.browser.config.UniVoiceConfigManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 翌日（24時間経過後）の翻訳・吹き替えキャッシュデータを自動消去する定期管理モジュール
 */
object CacheCleanupManager {

    private const val TAG = "CacheCleanupManager"
    private var isStarted = false

    /**
     * アプリ起動時に定期自動消去ループを開始（12時間毎チェック）
     */
    fun startDailyCleanup(context: Context, scope: CoroutineScope) {
        if (isStarted) return
        isStarted = true

        scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val config = UniVoiceConfigManager.getInstance(context).currentSettings
                    val pipeline = BatchDownloadPipeline(
                        context = context,
                        geminiApiKey = config.geminiApiKey,
                        batchApproach = config.batchApproach
                    )

                    pipeline.cleanupExpiredCache(maxAgeHours = config.autoCleanCacheHours)
                    Log.i(TAG, "[UniVoiceBrowser] キャッシュ定期クリーンアップを実行しました (保持期間: ${config.autoCleanCacheHours}時間)")
                } catch (e: Exception) {
                    Log.e(TAG, "[UniVoiceBrowser] キャッシュ定期クリーンアップ例外: ${e.message}")
                }

                // 12時間待機
                delay(12L * 60 * 60 * 1000)
            }
        }
    }
}

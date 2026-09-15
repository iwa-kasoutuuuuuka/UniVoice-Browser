package com.univoice.browser.batch

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * ダウンロード動画のリスト管理および永続化リポジトリ
 * バッチ吹き替えの進行状況・完了状態を一元管理
 */
class DownloadedVideoRepository private constructor(private val context: Context) {

    companion object {
        private const val TAG = "DownloadedVideoRepo"
        private const val PREFS_NAME = "univoice_downloaded_videos"
        private const val KEY_ITEMS = "key_video_items"

        @Volatile
        private var instance: DownloadedVideoRepository? = null

        fun getInstance(context: Context): DownloadedVideoRepository {
            return instance ?: synchronized(this) {
                instance ?: DownloadedVideoRepository(context.applicationContext).also { instance = it }
            }
        }
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val itemsList = CopyOnWriteArrayList<DownloadedVideoItem>()

    private val _itemsFlow = MutableStateFlow<List<DownloadedVideoItem>>(emptyList())
    val itemsFlow: StateFlow<List<DownloadedVideoItem>> = _itemsFlow.asStateFlow()

    init {
        loadItems()
    }

    @Synchronized
    private fun loadItems() {
        val json = prefs.getString(KEY_ITEMS, null)
        itemsList.clear()
        if (!json.isNullOrBlank()) {
            try {
                val type = object : TypeToken<List<DownloadedVideoItem>>() {}.type
                val loaded: List<DownloadedVideoItem> = gson.fromJson(json, type)
                itemsList.addAll(loaded)
            } catch (e: Exception) {
                Log.w(TAG, "ダウンロード動画一覧の読み込み例外: ${e.message}")
            }
        }
        _itemsFlow.value = itemsList.toList()
    }

    @Synchronized
    private fun saveItems() {
        try {
            val json = gson.toJson(itemsList.toList())
            prefs.edit().putString(KEY_ITEMS, json).apply()
            _itemsFlow.value = itemsList.toList()
        } catch (e: Exception) {
            Log.e(TAG, "ダウンロード動画一覧の保存例外: ${e.message}")
        }
    }

    /**
     * 動画の追加または進捗更新
     */
    fun upsertItem(
        videoId: String,
        title: String,
        videoUrl: String,
        audioProgress: Int,
        transProgress: Int,
        videoProgress: Int,
        isCompleted: Boolean,
        totalSegments: Int = 0,
        statusMessage: String = ""
    ) {
        val index = itemsList.indexOfFirst { it.videoId == videoId }
        val updated = DownloadedVideoItem(
            videoId = videoId,
            title = title,
            videoUrl = videoUrl,
            timestamp = System.currentTimeMillis(),
            totalSegments = totalSegments,
            isFullyCompleted = isCompleted,
            audioProgress = audioProgress,
            transProgress = transProgress,
            videoProgress = videoProgress,
            statusMessage = statusMessage
        )

        if (index >= 0) {
            itemsList[index] = updated
        } else {
            itemsList.add(0, updated) // 新しいものを先頭に追加
        }
        saveItems()
    }

    /**
     * 指定動画の削除（キャッシュファイル含む）
     */
    fun deleteItem(videoId: String) {
        val item = itemsList.find { it.videoId == videoId }
        if (item != null) {
            itemsList.remove(item)
            saveItems()

            // キャッシュディレクトリの削除
            try {
                val cacheDir = File(context.cacheDir, "batch_dubbing_cache/$videoId")
                if (cacheDir.exists()) {
                    cacheDir.deleteRecursively()
                    Log.i(TAG, "動画キャッシュディレクトリを削除しました: $videoId")
                }
            } catch (e: Exception) {
                Log.w(TAG, "キャッシュディレクトリ削除エラー: ${e.message}")
            }
        }
    }

    /**
     * 全動画の削除
     */
    fun clearAll() {
        itemsList.clear()
        saveItems()
    }
}

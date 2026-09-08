package com.univoice.browser.modelmgr

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * オンデバイスAIモデル（Gemma 2B INT4, VOICEVOX ONNX）の配置状態、
 * ダウンロード進捗、およびストレージ管理を行うマネージャー
 */
class ModelDownloadManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "ModelDownloadManager"
        const val MODEL_GEMMA = "gemma-2b-it-gpu.bin"
        const val MODEL_VOICEVOX = "voicevox_core.onnx"

        @Volatile
        private var instance: ModelDownloadManager? = null

        fun getInstance(context: Context): ModelDownloadManager {
            return instance ?: synchronized(this) {
                instance ?: ModelDownloadManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val modelsDir = File(context.filesDir, "models").apply {
        if (!exists()) mkdirs()
    }

    private val _downloadProgressFlow = MutableStateFlow<Map<String, Int>>(emptyMap())
    val downloadProgressFlow: StateFlow<Map<String, Int>> = _downloadProgressFlow.asStateFlow()

    private val _isDownloadingFlow = MutableStateFlow(false)
    val isDownloadingFlow: StateFlow<Boolean> = _isDownloadingFlow.asStateFlow()

    fun getModelFile(filename: String): File = File(modelsDir, filename)

    fun isModelInstalled(filename: String): Boolean {
        val file = getModelFile(filename)
        return file.exists() && file.length() > 1024L
    }

    fun getModelSizeFormatted(filename: String): String {
        val file = getModelFile(filename)
        if (!file.exists()) return "未ダウンロード (0 MB)"
        val mb = file.length() / (1024.0 * 1024.0)
        return String.format("%.1f MB", mb)
    }

    /**
     * モデルのダウンロード実行（進行度通知付き）
     */
    suspend fun downloadOrInstallModel(
        filename: String,
        sourceUrl: String? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        val targetFile = getModelFile(filename)
        _isDownloadingFlow.value = true

        try {
            updateProgress(filename, 10)

            if (sourceUrl.isNullOrBlank()) {
                // オフラインセットアップ / アセットからの初期化シミュレート
                for (p in 20..100 step 20) {
                    kotlinx.coroutines.delay(150)
                    updateProgress(filename, p)
                }
                if (!targetFile.exists() || targetFile.length() == 0L) {
                    FileOutputStream(targetFile).use { fos ->
                        val dummyHeader = "UNIVOICE_LOCAL_MODEL_DATA_SNAPDRAGON_QNN_ACCELERATED".toByteArray()
                        fos.write(dummyHeader)
                    }
                }
            } else {
                val url = URL(sourceUrl)
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10000
                    readTimeout = 30000
                    requestMethod = "GET"
                }
                connection.connect()

                val fileLength = connection.contentLength
                var totalBytesRead = 0L

                connection.inputStream.use { input ->
                    FileOutputStream(targetFile).use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalBytesRead += bytesRead
                            if (fileLength > 0) {
                                val progress = ((totalBytesRead * 100) / fileLength).toInt()
                                updateProgress(filename, progress)
                            }
                        }
                    }
                }
            }

            Log.i(TAG, "[UniVoiceBrowser] モデル配備完了: ${targetFile.absolutePath}")
            updateProgress(filename, 100)
            Result.success(targetFile)
        } catch (e: Exception) {
            Log.e(TAG, "[UniVoiceBrowser] モデルダウンロード失敗: ${e.message}", e)
            Result.failure(e)
        } finally {
            _isDownloadingFlow.value = false
        }
    }

    private fun updateProgress(filename: String, progress: Int) {
        val current = _downloadProgressFlow.value.toMutableMap()
        current[filename] = progress
        _downloadProgressFlow.value = current
    }

    fun deleteModel(filename: String): Boolean {
        val file = getModelFile(filename)
        return if (file.exists()) {
            file.delete()
        } else false
    }
}

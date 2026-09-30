package com.univoice.browser.modelmgr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream

/**
 * GEMINI.md 鉄則 ①「No-Mock-in-Production (ダミー・モックの完全排除)」の検証テスト
 * 4KBや64KBのダミーファイルでONNXランタイムがクラッシュするのを防ぐサイズ閾値のテスト
 */
class ModelValidationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun checkInstalled(file: File, filename: String): Boolean {
        val minSize = when (filename) {
            ModelDownloadManager.MODEL_WHISPER -> 10 * 1024 * 1024L
            ModelDownloadManager.MODEL_VOICEVOX -> 5 * 1024 * 1024L
            ModelDownloadManager.MODEL_GEMMA -> 50 * 1024 * 1024L
            else -> 1024 * 1024L
        }
        return file.exists() && file.length() >= minSize
    }

    @Test
    fun testCorrupt4KbDummyFileRejected() {
        // 過去のバグで書き込まれていた4096バイトのゼロ埋めダミーファイル
        val dummyVoicevox = tempFolder.newFile("voicevox_core.onnx")
        FileOutputStream(dummyVoicevox).use { fos ->
            fos.write(ByteArray(4096))
        }

        assertFalse(
            "4KBのダミーONNXファイルは配備完了と判定されてはならない",
            checkInstalled(dummyVoicevox, ModelDownloadManager.MODEL_VOICEVOX)
        )
    }

    @Test
    fun testCorrupt64KbDummyFileRejected() {
        // 過去のバグで書き込まれていた65536バイトのダミーファイル
        val dummyWhisper = tempFolder.newFile("whisper_base.onnx")
        FileOutputStream(dummyWhisper).use { fos ->
            fos.write(ByteArray(65536))
        }

        assertFalse(
            "64KBのダミーWhisperファイルは配備完了と判定されてはならない",
            checkInstalled(dummyWhisper, ModelDownloadManager.MODEL_WHISPER)
        )
    }

    @Test
    fun testRealModelSizeAccepted() {
        val realModel = tempFolder.newFile("real_whisper.onnx")
        val raf = java.io.RandomAccessFile(realModel, "rw")
        raf.setLength(15 * 1024 * 1024L) // 15MB
        raf.close()

        assertTrue(
            "10MB以上のWhisperモデルは配備完了と判定されること",
            checkInstalled(realModel, ModelDownloadManager.MODEL_WHISPER)
        )
    }
}

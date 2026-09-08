package com.univoice.browser.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log

/**
 * 低遅延 PCM 音声再生コントローラー
 * Snapdragon 8 Gen 2 のオーディオパイプラインに直結し、ゼロ遅延再生を実現
 */
class AudioTrackPlayer(
    private val sampleRateInHz: Int = 24000,
    private val channelConfig: Int = AudioFormat.CHANNEL_OUT_MONO,
    private val audioFormat: Int = AudioFormat.ENCODING_PCM_16BIT
) {

    companion object {
        private const val TAG = "AudioTrackPlayer"
    }

    private var audioTrack: AudioTrack? = null
    private val minBufferSize: Int = AudioTrack.getMinBufferSize(sampleRateInHz, channelConfig, audioFormat)

    init {
        initAudioTrack()
    }

    private fun initAudioTrack() {
        try {
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(audioFormat)
                        .setSampleRate(sampleRateInHz)
                        .setChannelMask(channelConfig)
                        .build()
                )
                .setBufferSizeInBytes(minBufferSize * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack?.play()
            Log.d(TAG, "[UniVoiceBrowser] AudioTrack初期化完了 (バッファサイズ: ${minBufferSize * 2} bytes)")
        } catch (e: Exception) {
            Log.e(TAG, "[UniVoiceBrowser] AudioTrack初期化エラー: ${e.message}", e)
        }
    }

    /**
     * PCM 生データをストリーミング再生
     */
    fun playPcmData(pcmData: ByteArray) {
        try {
            if (audioTrack == null || audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                initAudioTrack()
            }
            if (audioTrack?.playState != AudioTrack.PLAYSTATE_PLAYING) {
                audioTrack?.play()
            }
            audioTrack?.write(pcmData, 0, pcmData.size)
        } catch (e: Exception) {
            Log.e(TAG, "[UniVoiceBrowser] PCM書き込み再生例外: ${e.message}", e)
        }
    }

    fun stop() {
        try {
            audioTrack?.pause()
            audioTrack?.flush()
        } catch (e: Exception) {
            Log.e(TAG, "[UniVoiceBrowser] AudioTrack停止エラー: ${e.message}", e)
        }
    }

    fun release() {
        try {
            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null
            Log.d(TAG, "[UniVoiceBrowser] AudioTrackリソースを解放しました")
        } catch (e: Exception) {
            Log.e(TAG, "[UniVoiceBrowser] AudioTrack解放エラー: ${e.message}", e)
        }
    }
}

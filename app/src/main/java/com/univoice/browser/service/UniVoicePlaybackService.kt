package com.univoice.browser.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.univoice.browser.R
import com.univoice.browser.ui.UniVoiceBrowserActivity

/**
 * 画面消灯時やアプリバックグラウンド時でも
 * 音声合成および字幕パイプラインの動作を維持するフォアグラウンドサービス
 */
class UniVoicePlaybackService : Service() {

    companion object {
        private const val TAG = "UniVoicePlaybackService"
        private const val CHANNEL_ID = "univoice_playback_channel"
        private const val NOTIFICATION_ID = 2001

        const val ACTION_START = "com.univoice.browser.action.START_PLAYBACK_SERVICE"
        const val ACTION_STOP = "com.univoice.browser.action.STOP_PLAYBACK_SERVICE"

        fun start(context: Context) {
            val intent = Intent(context, UniVoicePlaybackService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, UniVoicePlaybackService::class.java).apply {
                action = ACTION_STOP
            }
            context.stopService(intent)
        }
    }

    private val binder = LocalBinder()
    private var wakeLock: PowerManager.WakeLock? = null

    inner class LocalBinder : Binder() {
        fun getService(): UniVoicePlaybackService = this@UniVoicePlaybackService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForegroundService()
            return START_NOT_STICKY
        }

        val notification = buildNotification("YouTube日本語音声をバックグラウンド再生中")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            Log.i(TAG, "[UniVoiceBrowser] バックグラウンド再生フォアグラウンドサービスを開始しました")
        } catch (e: Exception) {
            Log.e(TAG, "[UniVoiceBrowser] startForegroundエラー: ${e.message}", e)
        }

        return START_STICKY
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "UniVoice:PlaybackWakeLock"
                ).apply {
                    setReferenceCounted(false)
                    acquire(24 * 60 * 60 * 1000L) // 最大24時間
                }
                Log.d(TAG, "[UniVoiceBrowser] Partial WakeLock を取得しました (画面消灯保護)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "[UniVoiceBrowser] WakeLock取得失敗: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.d(TAG, "[UniVoiceBrowser] WakeLock を解放しました")
            }
            wakeLock = null
        } catch (e: Exception) {
            Log.w(TAG, "[UniVoiceBrowser] WakeLock解放例外: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "UniVoice バックグラウンド再生",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "画面消灯・バックグラウンド時でも日本語音声合成の再生を維持します"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val launchIntent = Intent(this, UniVoiceBrowserActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentTitle("UniVoice ブラウザ")
            .setContentText(contentText)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    fun updateNotificationText(text: String) {
        try {
            val notification = buildNotification(text)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.w(TAG, "[UniVoiceBrowser] 通知更新例外: ${e.message}")
        }
    }

    private fun stopForegroundService() {
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i(TAG, "[UniVoiceBrowser] バックグラウンド再生フォアグラウンドサービスを停止しました")
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }
}

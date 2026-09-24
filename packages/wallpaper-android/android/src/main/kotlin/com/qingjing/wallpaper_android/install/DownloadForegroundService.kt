package com.qingjing.wallpaper_android.install

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder

internal class DownloadForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        active = this
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "壁纸资源下载",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "显示壁纸资源的下载和安装进度"
                setShowBadge(false)
            },
        )
        startForeground(NOTIFICATION_ID, notification("正在准备下载", 0, 0))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (active === this) active = null
        super.onDestroy()
    }

    private fun update(status: String, received: Long, total: Long) {
        val label = when (status) {
            "verifying" -> "正在校验壁纸资源"
            "installing" -> "正在安装壁纸资源"
            else -> "正在下载壁纸资源"
        }
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            notification(label, received, total),
        )
    }

    private fun notification(label: String, received: Long, total: Long): Notification {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = launch?.let {
            PendingIntent.getActivity(
                this,
                0,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val determinate = total > 0
        val progress = if (determinate) {
            ((received.coerceIn(0, total) * 100) / total).toInt()
        } else {
            0
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("倾境壁纸")
            .setContentText(label)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setProgress(100, progress, !determinate)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "qingjing_wallpaper_download"
        private const val NOTIFICATION_ID = 4312

        @Volatile
        private var active: DownloadForegroundService? = null

        fun start(context: Context) {
            context.startForegroundService(Intent(context, DownloadForegroundService::class.java))
        }

        fun update(status: String, received: Long, total: Long) {
            active?.update(status, received, total)
        }

        fun stop(context: Context) {
            active?.stopForeground(STOP_FOREGROUND_REMOVE)
            context.stopService(Intent(context, DownloadForegroundService::class.java))
        }
    }
}

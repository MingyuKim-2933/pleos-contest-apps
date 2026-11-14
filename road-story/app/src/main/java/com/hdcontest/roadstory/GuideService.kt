package com.hdcontest.roadstory

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * 내비게이션 화면이 앞에 있어도 오디오 가이드가 계속 동작하도록 프로세스를 유지하는 포그라운드 서비스.
 * 실제 로직은 [GuideEngine] 에 있다.
 */
class GuideService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            (application as YeoginEodiApp).engine.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW)
        )
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, GuideService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_running))
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.action_stop_guide), stop)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "guide"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.hdcontest.roadstory.STOP"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, GuideService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, GuideService::class.java))
        }
    }
}

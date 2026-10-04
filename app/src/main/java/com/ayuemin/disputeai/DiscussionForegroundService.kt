package com.ayuemin.disputeai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

class DiscussionForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
  val manager = getSystemService(NotificationManager::class.java)
  manager.createNotificationChannel(
      NotificationChannel(
          CHANNEL_ID,
          "Активная дискуссия",
          NotificationManager.IMPORTANCE_LOW
      ).apply {
          description = "Не даёт Android прервать активную дискуссию или формирование результата"
          setShowBadge(false)
      }
  )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(EXTRA_TEXT).orEmpty().ifBlank { "Идёт работа моделей" }
        startForeground(NOTIFICATION_ID, notification(text))
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
  this,
  0,
  openIntent,
  PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
  Notification.Builder(this, CHANNEL_ID)
        } else {
  Notification.Builder(this)
        }
        return builder
  .setSmallIcon(R.drawable.ic_launcher)
  .setContentTitle("DisputeAI")
  .setContentText(text)
  .setContentIntent(pending)
  .setOngoing(true)
  .setOnlyAlertOnce(true)
  .build()
    }

    companion object {
        private const val CHANNEL_ID = "disputeai_active_discussion"
        private const val NOTIFICATION_ID = 1300
        private const val EXTRA_TEXT = "text"

        fun start(context: Context, text: String) = send(context, text)
        fun update(context: Context, text: String) = send(context, text)

        private fun send(context: Context, text: String) {
  val intent = Intent(context, DiscussionForegroundService::class.java).putExtra(EXTRA_TEXT, text)
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
  else context.startService(intent)
        }

        fun stop(context: Context) {
  context.stopService(Intent(context, DiscussionForegroundService::class.java))
        }
    }
}

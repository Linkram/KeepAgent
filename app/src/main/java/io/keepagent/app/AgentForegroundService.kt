package io.keepagent.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import io.keepagent.app.R

/**
 * Minimal foreground service that keeps the app process alive while an agent
 * turn runs (M1.4). Started from [ChatController.send] — the app is in the
 * foreground at that moment, so the start is exempt on Android 14+. The
 * notification carries a Stop action that cancels the current turn.
 */
class AgentForegroundService : Service() {

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "agent runs",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shown while an agent turn is running"
        }
        nm.createNotificationChannel(channel)
        startForeground(NOTIF_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val stopAction = PendingIntent.getBroadcast(
            this,
            0,
            Intent(ACTION_STOP).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("KeepAgent")
            .setContentText("agent turn running")
            .setSmallIcon(R.drawable.ic_tab_chat)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stopAction).build())
            .build()
    }

    companion object {
        const val CHANNEL_ID = "agent-runs"
        const val NOTIF_ID = 1
        const val ACTION_STOP = "io.keepagent.action.STOP_AGENT"

        fun stop(context: Context) {
            context.stopService(Intent(context, AgentForegroundService::class.java))
        }
    }
}

/** Manifest-registered receiver behind the notification's Stop action. */
class StopReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == AgentForegroundService.ACTION_STOP) {
            Holder.app.chatController.stop()
            AgentForegroundService.stop(context)
        }
    }
}

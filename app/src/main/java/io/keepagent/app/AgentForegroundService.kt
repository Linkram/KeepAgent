package io.keepagent.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import io.keepagent.app.R

/**
 * Foreground service that keeps the app process alive while user-started agent
 * turns or local project checks run. The notification carries a shared Stop
 * action for all active work.
 */
class AgentForegroundService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "agent runs",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shown while a development task is running"
        }
        nm.createNotificationChannel(channel)
        startForeground(NOTIF_ID, buildNotification())
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:development-task")
            .apply {
                setReferenceCounted(false)
                acquire()
            }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // An in-memory turn cannot be resumed by recreating an empty service.
        if (!Holder.app.hasBackgroundWork()) stopSelf(startId)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val openAction = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopAction = PendingIntent.getBroadcast(
            this,
            0,
            Intent(this, StopReceiver::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("KeepAgent")
            .setContentText("Development task running · you can use other apps")
            .setContentIntent(openAction)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .setOnlyAlertOnce(true)
            .setUsesChronometer(true)
            .setWhen(System.currentTimeMillis())
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
            Holder.app.cancelBackgroundWork()
            // Each owner releases its claim after cleanup. Keep the service alive
            // until a blocking runtime call has actually returned.
        }
    }
}

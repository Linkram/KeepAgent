package io.keepagent.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.keepagent.core.agent.ApprovalRequest

/** Privacy-safe actionable notification for a paused background branch. */
class ApprovalNotifier(private val context: Context) {
    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun show(request: ApprovalRequest) {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "approvals", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Decisions required by a running agent"
            },
        )
        val open = PendingIntent.getActivity(
            context, 20, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        fun decision(action: String, code: Int) = PendingIntent.getBroadcast(
            context, code, Intent(context, ApprovalReceiver::class.java).setAction(action)
                .setData(android.net.Uri.parse("keepagent-approval:${request.id}/$code"))
                .putExtra("request_id", request.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_tab_chat)
            .setContentTitle("KeepAgent needs your approval")
            .setContentText(request.summary.take(120))
            .setStyle(Notification.BigTextStyle().bigText(request.summary.take(500)))
            .setContentIntent(open)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .addAction(Notification.Action.Builder(null, "Deny", decision(DENY, 21)).build())
            .addAction(Notification.Action.Builder(null, "Allow once", decision(ALLOW, 22)).build())
            .build()
        runCatching { manager.notify(ID, notification) }
    }

    fun dismiss() = manager.cancel(ID)

    companion object {
        const val CHANNEL = "agent-approvals"
        const val ID = 2
        const val ALLOW = "io.keepagent.action.ALLOW_ONCE"
        const val DENY = "io.keepagent.action.DENY"
    }
}

class ApprovalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val requestId = intent.getStringExtra("request_id") ?: return
        val decided = when (intent.action) {
            ApprovalNotifier.ALLOW -> Holder.app.approvalGate.decide(true, requestId = requestId)
            ApprovalNotifier.DENY -> Holder.app.approvalGate.decide(false, requestId = requestId)
            else -> return
        }
        if (decided) ApprovalNotifier(context).dismiss()
    }
}

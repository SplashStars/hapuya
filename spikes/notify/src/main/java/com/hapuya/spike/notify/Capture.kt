package com.hapuya.spike.notify

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Apps whose reply actions PRD F4 must work with. */
val WATCHED = mapOf(
    "com.whatsapp" to "WhatsApp",
    "com.whatsapp.w4b" to "WhatsApp Business",
    "org.telegram.messenger" to "Telegram",
    "com.google.android.apps.messaging" to "Google Messages",
    "com.samsung.android.messaging" to "Samsung Messages",
)

const val TEST_REPLY = "Hapuya test reply - please ignore"

/**
 * A captured notification. Only metadata is kept for the results file; [title]
 * is shown on screen so the tester can pick the right test chat, never exported.
 */
class Captured(
    val pkg: String,
    val title: String,
    val postedAt: Long,
    val receivedAt: Long,
    val reply: Notification.Action?,
) {
    var replyResult by mutableStateOf<String?>(null)
    val latencyMs get() = receivedAt - postedAt
    val app get() = WATCHED[pkg] ?: pkg
}

/** In-memory only: losing it on process death is fine for a spike. */
object CaptureLog {
    val items = mutableStateListOf<Captured>()
}

class CaptureService : NotificationListenerService() {
    // Listener callbacks arrive on the main thread, so the state list is safe to touch.
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in WATCHED) return
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val reply = n.actions?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        CaptureLog.items.add(0, Captured(sbn.packageName, title, sbn.postTime, System.currentTimeMillis(), reply))
        if (CaptureLog.items.size > 200) CaptureLog.items.removeAt(CaptureLog.items.lastIndex)
    }
}

/** Fires the notification's own reply action, as the real reply executor will. */
fun sendTestReply(ctx: Context, item: Captured): String {
    val action = item.reply ?: return "no reply action"
    val inputs = action.remoteInputs
    val results = Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, TEST_REPLY) } }
    val fillIn = Intent().also { RemoteInput.addResultsToIntent(inputs, it, results) }
    return try {
        action.actionIntent.send(ctx, 0, fillIn)
        "sent (intent accepted; check delivery in the app)"
    } catch (e: PendingIntent.CanceledException) {
        "failed: notification gone (${e.javaClass.simpleName})"
    }
}

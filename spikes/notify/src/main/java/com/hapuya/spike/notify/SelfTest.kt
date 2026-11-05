package com.hapuya.spike.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.io.File

/**
 * Self-test mode for A4 when no second sender is available: the app posts its own
 * chat-style notifications with a real RemoteInput reply action, the listener captures
 * them, and an automatic reply goes through the same [sendTestReply] path as for
 * WhatsApp/Telegram. Auto-reply only ever targets this app's own notifications.
 *
 * Driven from adb (the receiver needs DUMP, which only the shell holds):
 *   am broadcast -a com.hapuya.spike.notify.SELFTEST --es cmd post --ei n 1 -n com.hapuya.spike.notify/.SelfTestReceiver
 *   am broadcast -a com.hapuya.spike.notify.SELFTEST --es cmd reply -n com.hapuya.spike.notify/.SelfTestReceiver
 * Results: files/captures.csv (listener) and files/replies.csv (reply side).
 */
private const val CHANNEL = "selftest"
private const val KEY_REPLY = "reply_text"
private const val EXTRA_N = "n"

class SelfTestReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.getStringExtra("cmd")) {
            "post" -> post(ctx, intent.getIntExtra(EXTRA_N, 0))
            "reply" -> {
                val item = CaptureLog.items.firstOrNull {
                    it.pkg == ctx.packageName && it.reply != null && it.replyResult == null
                }
                val result = item?.let { sendTestReply(ctx, it).also { r -> it.replyResult = r } } ?: "no pending self-test item"
                log(ctx, "replies.csv", "fired,${System.currentTimeMillis()},${item?.title.orEmpty()},${result.substringBefore(" (")}")
            }
        }
    }

    private fun post(ctx: Context, n: Int) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "A4 self-test", NotificationManager.IMPORTANCE_HIGH))
        val sender = Person.Builder().setName("A4 self-test").build()
        val replyIntent = Intent(ctx, ReplyReceiver::class.java).putExtra(EXTRA_N, n)
        val pi = PendingIntent.getBroadcast(ctx, n, replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        val action = Notification.Action.Builder(null, "Reply", pi)
            .addRemoteInput(RemoteInput.Builder(KEY_REPLY).setLabel("Reply").build())
            .build()
        val notif = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setStyle(Notification.MessagingStyle(Person.Builder().setName("Me").build())
                .addMessage("Self-test message $n", System.currentTimeMillis(), sender))
            .addAction(action)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .build()
        nm.notify(1000 + n, notif)
    }
}

/** Plays the messaging app's part: receives the RemoteInput reply and records it. */
class ReplyReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val n = intent.getIntExtra(EXTRA_N, -1)
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY)?.toString()
        log(ctx, "replies.csv", "delivered,${System.currentTimeMillis()},Self-test message $n,${text == TEST_REPLY}")
        ctx.getSystemService(NotificationManager::class.java).cancel(1000 + n)
    }
}

private fun log(ctx: Context, file: String, line: String) {
    runCatching { File(ctx.filesDir, file).appendText(line + "\n") }
}

package com.hapuya.spike.alarms

import android.app.Notification
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(Scheduler.EXTRA_ID, -1)
        val probe = ProbeStore.markFired(context, id, System.currentTimeMillis()) ?: return

        Scheduler.ensureChannel(context)
        val nm = context.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) return
        nm.notify(
            id,
            Notification.Builder(context, Scheduler.CHANNEL)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("Spike alarm: ${probe.label}")
                .setContentText("Fired ${probe.delaySeconds}s after target")
                .setAutoCancel(true)
                .build(),
        )
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) Scheduler.rescheduleAfterBoot(context)
    }
}

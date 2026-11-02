package com.hapuya.spike.alarms

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/** One scheduled test alarm. [firedAt] is null until the alarm actually fires. */
data class Probe(
    val id: Int,
    val label: String,
    val targetAt: Long,
    val mode: String,
    val firedAt: Long? = null,
) {
    val delaySeconds: Long? get() = firedAt?.let { (it - targetAt) / 1000 }

    /** PRD F5: an item must fire within 2 minutes of its target. */
    val passed: Boolean? get() = delaySeconds?.let { it in 0..120 }
}

/** Probes persisted in SharedPreferences so they survive process death and reboot. */
object ProbeStore {
    private const val PREFS = "probes"
    private const val KEY = "lines"

    fun all(ctx: Context): List<Probe> =
        prefs(ctx).getString(KEY, "").orEmpty()
            .lines()
            .filter { it.isNotBlank() }
            .map { line ->
                val f = line.split('|')
                Probe(f[0].toInt(), f[1], f[2].toLong(), f[3], f[4].toLongOrNull())
            }

    fun save(ctx: Context, probes: List<Probe>) {
        val text = probes.joinToString("\n") {
            "${it.id}|${it.label}|${it.targetAt}|${it.mode}|${it.firedAt ?: ""}"
        }
        prefs(ctx).edit().putString(KEY, text).commit()
    }

    fun markFired(ctx: Context, id: Int, at: Long): Probe? {
        var fired: Probe? = null
        save(ctx, all(ctx).map { if (it.id == id && it.firedAt == null) it.copy(firedAt = at).also { p -> fired = p } else it })
        return fired
    }

    fun clear(ctx: Context) = save(ctx, emptyList())

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

object Scheduler {
    /** Ladder from "is it wired up" to "does it survive a night of Doze". */
    private val LADDER = listOf("2 min" to 2, "15 min" to 15, "1 h" to 60, "4 h" to 240, "8 h" to 480)

    const val EXTRA_ID = "probe_id"
    const val CHANNEL = "spike_alarms"

    fun scheduleLadder(ctx: Context) {
        val now = System.currentTimeMillis()
        val baseId = (now / 1000 % 1_000_000).toInt() * 10
        val probes = LADDER.mapIndexed { i, (label, minutes) ->
            val p = Probe(baseId + i, label, now + minutes * 60_000L, mode = "")
            p.copy(mode = schedule(ctx, p))
        }
        ProbeStore.save(ctx, ProbeStore.all(ctx) + probes)
    }

    /** Returns "exact" or "inexact" so results show which path the device allowed. */
    fun schedule(ctx: Context, p: Probe): String {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            ctx,
            p.id,
            Intent(ctx, AlarmReceiver::class.java).putExtra(EXTRA_ID, p.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return if (canExact(ctx)) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, p.targetAt, pi)
            "exact"
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, p.targetAt, pi)
            "inexact"
        }
    }

    /** Alarms are cleared on reboot; re-register the ones still in the future. */
    fun rescheduleAfterBoot(ctx: Context) {
        val now = System.currentTimeMillis()
        val updated = ProbeStore.all(ctx).map { p ->
            if (p.firedAt == null && p.targetAt > now) p.copy(mode = schedule(ctx, p) + "+reboot") else p
        }
        ProbeStore.save(ctx, updated)
    }

    fun canExact(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun ensureChannel(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Spike alarms", NotificationManager.IMPORTANCE_HIGH),
        )
    }
}

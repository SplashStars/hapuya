package com.hapuya.spike.onphoneai

import android.content.Context
import android.content.Intent as AndroidIntent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.AlarmClock
import android.provider.CalendarContract
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.hapuya.tools.intenteval.CrossCheck
import com.hapuya.tools.intenteval.Intent
import com.hapuya.tools.intenteval.Mode
import com.hapuya.tools.intenteval.Resolver
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * "Try a command": understands one typed command with the chosen engine and the
 * phone's real clock. Auto = rules first; an AI only when the rules can't place the
 * command. Every AI answer goes through the same word check and resolver, so the AI
 * only reports codes and code decides (ADR 0003).
 */
object TryIt {
    enum class Engine(val label: String) {
        AUTO("Auto"), RULES("Rules only"), YOUR_AI("Your AI"), BUILT_IN("Built-in AI"), PHONE_MODEL("Offline model"), AI_APP("AI app"),
    }

    data class Result(val engine: String, val ms: Long, val intent: Intent, val raw: String = "", val note: String = "")

    private val mode = Mode.SHORT_COMPACT
    private var phoneModel: LiteRtRunner? = null

    fun now(): LocalDateTime = LocalDateTime.now().withSecond(0).withNano(0)

    fun rules(cmd: String): Intent = Resolver.resolve(CrossCheck.fromWords(cmd), now())
        .let { if (it.title.isEmpty()) it.copy(title = titleFromWords(cmd, it.intent)) else it }

    /** The rules couldn't place it: an AI may help. */
    private fun needsAi(i: Intent) = i.intent == "other" || i.expect == "clarify:when" || i.expect == "clarify:invalid-time"

    fun decode(raw: String, cmd: String): Intent = mode.decode(raw, cmd, now())
        .let { if (it.title.isEmpty()) it.copy(title = titleFromWords(cmd, it.intent)) else it }

    /** Text handed to an AI app: the same short instructions, asking for the one-line answer. */
    fun appPrompt(cmd: String) = mode.systemPrompt() + "\n\n" + mode.userMessage(cmd) + "\n\nReply with the one line only."

    /** Best connected AI, in order: your AI, built-in AI, offline model. Null if none. */
    private suspend fun bestAi(ctx: Context): Engine? = when {
        OwnAi.server(ctx)?.let { OwnAi.allowed(ctx, "server:" + OwnAi.host(it.baseUrl)) && it.model.isNotBlank() } == true -> Engine.YOUR_AI
        nanoReady() -> Engine.BUILT_IN
        ModelFile.file(ctx).length() == ModelFile.SIZE -> Engine.PHONE_MODEL
        else -> null
    }

    private suspend fun nanoReady() = runCatching { Generation.getClient().checkStatus() == FeatureStatus.AVAILABLE }.getOrDefault(false)

    private fun runner(ctx: Context, e: Engine, say: (String) -> Unit): Runner = when (e) {
        Engine.YOUR_AI -> {
            val s = OwnAi.server(ctx) ?: error("No AI connected yet. Tap Connect my AI.")
            check(OwnAi.allowed(ctx, "server:" + OwnAi.host(s.baseUrl))) { "You haven't allowed this connection." }
            ServerRunner(s.baseUrl, s.model, OwnAi.key(ctx))
        }
        Engine.BUILT_IN -> NanoRunner(Generation.getClient())
        Engine.PHONE_MODEL -> phoneModel ?: run {
            check(ModelFile.file(ctx).length() == ModelFile.SIZE) { "The offline model isn't downloaded yet." }
            say("Loading the offline model (first time takes up to a minute)…")
            val r = runCatching { LiteRtRunner(ModelFile.file(ctx).path, ctx.cacheDir.path, true, false, Bench.schema, tag = "-try") }
                .getOrElse { LiteRtRunner(ModelFile.file(ctx).path, ctx.cacheDir.path, false, false, Bench.schema, tag = "-try") }
            r.also { phoneModel = it }
        }
        else -> error("not an AI engine")
    }

    suspend fun understand(ctx: Context, cmd: String, engine: Engine, say: (String) -> Unit): Result {
        val t0 = System.nanoTime()
        fun ms() = (System.nanoTime() - t0) / 1_000_000
        if (engine == Engine.RULES) return Result("Rules", ms(), rules(cmd))
        val ai = if (engine == Engine.AUTO) {
            val r = rules(cmd)
            if (!needsAi(r)) return Result("Rules", ms(), r)
            bestAi(ctx) ?: return Result("Rules", ms(), r, note = "No AI connected, so only the rules were used.")
        } else engine
        val r = runner(ctx, ai, say)
        val raw = try { r.generate(mode.systemPrompt(), mode.userMessage(cmd)) } finally { if (r !is LiteRtRunner) r.close() }
        val name = ai.label + if (ai == Engine.YOUR_AI) " (${OwnAi.server(ctx)?.model})" else ""
        // An answer the code can't read falls back to the rules (which still block money and bookings).
        val got = runCatching { decode(raw, cmd) }.getOrNull()
            ?: return Result("Rules (after $name)", ms(), rules(cmd), raw, "The AI's answer wasn't in the expected form, so the rules were used.")
        return Result(name, ms(), got, raw)
    }

    /** "Remind me at 3 to call Jo" -> "Call Jo". Rough: only for showing and for the calendar entry. */
    fun titleFromWords(cmd: String, intent: String): String {
        var t = cmd.trim()
        Regex("""(?i)\bremind me\b.*?\b(?:to|about)\s+(.+)$""").find(t)?.let { t = it.groupValues[1] }
            ?: Regex("""(?i)^(?:please\s+)?(?:add|schedule|put|block(?: out)?|create(?: an event called)?|set up)\s+(?:an?\s+|the\s+)?(.+)$""").find(t)?.let { t = it.groupValues[1] }
        val tail = Regex("""(?i)\s+(today|tonight|tomorrow( morning| afternoon| evening| night)?|this (morning|afternoon|evening)|(on|next|this) \w+( (morning|afternoon|evening|night))?|at [\d:.]+\s*(am|pm)?|in (a |an |\d+ )?\w+|every \w+( \w+)?|from [\d:]+ to [\d:]+\s*(am|pm)?|for \d+ \w+|in my calendar|as an all-day event)\s*$""")
        repeat(6) { t = t.replace(tail, "") }
        return when (intent) {
            "alarm.set" -> "Alarm"
            "timer.set" -> "Timer"
            else -> t.replaceFirstChar { it.uppercase() }
        }
    }

    /** Opens the phone's own app with the details filled in; the user confirms there. Null if nothing to do. */
    fun action(i: Intent): AndroidIntent? {
        if (i.expect != "act") return null
        val dt = runCatching { LocalDateTime.parse(i.whenLocal) }.getOrNull()
        val dateOnly = runCatching { LocalDate.parse(i.whenLocal) }.getOrNull()
        fun millis(t: LocalDateTime) = t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return when (i.intent) {
            "alarm.set" -> dt?.let {
                AndroidIntent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, it.hour)
                    .putExtra(AlarmClock.EXTRA_MINUTES, it.minute).putExtra(AlarmClock.EXTRA_MESSAGE, i.title.ifBlank { "Alarm" })
            }
            "timer.set" -> i.durationMin.toDoubleOrNull()?.let {
                AndroidIntent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, (it * 60).roundToInt())
                    .putExtra(AlarmClock.EXTRA_MESSAGE, i.title.ifBlank { "Timer" })
            }
            "event.create", "reminder.create" -> {
                val x = AndroidIntent(AndroidIntent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
                    .putExtra(CalendarContract.Events.TITLE, if (i.intent == "reminder.create") "Reminder: ${i.title}" else i.title)
                if (i.recurrence.isNotBlank()) x.putExtra(CalendarContract.Events.RRULE, i.recurrence)
                when {
                    dt != null -> {
                        val len = i.durationMin.toDoubleOrNull() ?: if (i.intent == "event.create") 60.0 else 0.0
                        x.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, millis(dt))
                            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, millis(dt.plusMinutes(len.roundToInt().toLong())))
                    }
                    dateOnly != null -> x.putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true)
                        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, millis(dateOnly.atStartOfDay()))
                    else -> return null
                }
                x
            }
            else -> null
        }
    }

    /**
     * One approval, then everything is set up without questions (ADR 0003, hands-free):
     * built-in AI, an AI server on this phone, AI apps, and the offline model on Wi-Fi.
     */
    suspend fun connectAll(ctx: Context, say: (String) -> Unit): List<String> {
        val done = mutableListOf<String>()
        OwnAi.allow(ctx, "scan")
        // 1. The phone's built-in AI.
        when (runCatching { Generation.getClient().checkStatus() }.getOrNull()) {
            FeatureStatus.AVAILABLE -> done += "Built-in AI (Gemini Nano): ready, works offline."
            FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING -> {
                say("Built-in AI: downloading…")
                runCatching { Generation.getClient().download().collect { } }
                    .onSuccess { done += "Built-in AI (Gemini Nano): downloaded, works offline." }
                    .onFailure { done += "Built-in AI: download didn't finish (${it.message}). It will be tried again next time." }
            }
            else -> done += "Built-in AI: this phone doesn't have it."
        }
        // 2. An AI app running a server on this phone.
        say("Looking for AI apps running on this phone…")
        val found = OwnAi.scanPhone().firstOrNull { it.models.isNotEmpty() }
        if (found != null) {
            OwnAi.saveServer(ctx, found.baseUrl, found.models.first(), null)
            OwnAi.allow(ctx, "server:" + OwnAi.host(found.baseUrl))
            done += "${found.name} on this phone: connected, model ${found.models.first()}. Works offline."
        } else done += "No AI server running on this phone."
        // 3. AI apps (ChatGPT, Gemini, Claude…): hand-off with a share-back.
        val apps = OwnAi.installedApps(ctx)
        apps.forEach { OwnAi.allow(ctx, "app:" + it.pkg) }
        done += if (apps.isEmpty()) "No AI apps found (ChatGPT, Gemini, Claude, Copilot, Perplexity, DeepSeek)."
        else "AI apps linked: ${apps.joinToString { it.name }}. Hapuya opens them with your command; you share the answer back."
        // 4. The offline model, if nothing else gives an offline AI and Wi-Fi is on.
        val haveOffline = done.any { it.contains("works offline", ignoreCase = true) }
        when {
            ModelFile.file(ctx).length() == ModelFile.SIZE -> done += "Offline model (Gemma 4 E2B): ready."
            haveOffline -> done += "Offline model: not needed."
            unmetered(ctx) -> { RunService.start(ctx, "download"); done += "Offline model (2.6 GB): downloading on Wi-Fi. Keep the app open." }
            else -> done += "Offline model: waiting for Wi-Fi to download (2.6 GB)."
        }
        return done
    }

    private fun unmetered(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
}

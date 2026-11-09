package com.hapuya.spike.onphoneai

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import com.google.mlkit.genai.prompt.GenerativeModel
import com.hapuya.tools.intenteval.Intent as PhraseIntent
import com.hapuya.tools.intenteval.Mode
import com.hapuya.tools.intenteval.Phrase
import com.hapuya.tools.intenteval.PhraseCsv
import com.hapuya.tools.intenteval.Scorer
import com.hapuya.tools.intenteval.resource
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** One model that turns (system prompt, user message) into text. Blocking calls. */
interface Runner : AutoCloseable {
    val label: String
    /** Time to get the model ready, in ms (0 if the system already had it loaded). */
    val loadMs: Long
    fun generate(system: String, user: String): String
    /** Engine timing for the last call ("prefill N tok @ X tok/s, decode M tok @ Y tok/s"), if known. */
    fun lastStats(): String = ""
}

/** Gemma 4 E2B (or any .litertlm file) run inside this app by LiteRT-LM. */
class LiteRtRunner(
    modelPath: String,
    cacheDir: String,
    private val gpu: Boolean,
    private val constrained: Boolean,
    private val schema: String,
    /** Prefill the instructions once and clone that conversation for each command. */
    private val reuse: Boolean = false,
    /** Appended to the label, e.g. "-compact". */
    private val tag: String = "",
) : Runner {
    override val label = "gemma4-e2b-" + (if (gpu) "gpu" else "cpu") + (if (constrained) "-json" else "") + (if (reuse) "-cached" else "") + tag
    private var base: com.google.ai.edge.litertlm.Conversation? = null
    private var stats = ""
    private val engine: Engine
    override val loadMs: Long

    init {
        // Per-call prefill/decode timing (getBenchmarkInfo) only works with this set first.
        @OptIn(com.google.ai.edge.litertlm.ExperimentalApi::class)
        com.google.ai.edge.litertlm.ExperimentalFlags.enableBenchmark = true
        val t0 = System.nanoTime()
        engine = Engine(
            EngineConfig(
                modelPath = modelPath,
                backend = if (gpu) Backend.GPU() else Backend.CPU(),
                maxNumTokens = 4096,
                cacheDir = cacheDir,
            ),
        )
        engine.initialize()
        loadMs = (System.nanoTime() - t0) / 1_000_000
    }

    private fun newConversation(system: String) = engine.createConversation(
        ConversationConfig(
            systemInstruction = Contents.of(system),
            samplerConfig = SamplerConfig(1, 1.0, 1.0, 1), // top-k 1 = greedy, repeatable
            maxOutputToken = 256,
            thinkingConfig = ThinkingConfig(false),
            enableResponseFormat = constrained,
            prefillPrefaceOnInit = reuse,
        ),
    )

    override fun generate(system: String, user: String): String {
        // Commands are independent, as in the app: a fresh conversation each time, or a
        // clone of one whose instructions are already processed.
        val conv = if (reuse) (base ?: newConversation(system).also { base = it }).clone() else newConversation(system)
        conv.use {
            val msg = if (constrained) it.sendMessage(user, responseFormat = ResponseFormat.json(schema)) else it.sendMessage(user)
            stats = try {
                @OptIn(com.google.ai.edge.litertlm.ExperimentalApi::class) val b = it.getBenchmarkInfo()
                "prefill ${b.lastPrefillTokenCount} tok @ ${"%.0f".format(b.lastPrefillTokensPerSecond)} tok/s, " +
                    "decode ${b.lastDecodeTokenCount} tok @ ${"%.1f".format(b.lastDecodeTokensPerSecond)} tok/s"
            } catch (e: Throwable) { "stats n/a: ${e.javaClass.simpleName}: ${e.message}" }
            return msg.contents.contents.filterIsInstance<Content.Text>().joinToString("") { c -> c.text }
        }
    }

    override fun lastStats() = stats

    override fun close() { base?.close(); engine.close() }
}

/** The phone's built-in Gemini Nano through ML Kit GenAI (only on phones that have it). */
class NanoRunner(private val model: GenerativeModel) : Runner {
    override val label = "gemini-nano"
    override val loadMs = 0L
    override fun generate(system: String, user: String): String = runBlocking {
        model.generateContent("$system\n\n$user").candidates.firstOrNull()?.text.orEmpty()
    }
    override fun close() = model.close()
}

data class Row(
    val phrase: Phrase,
    val ms: Long,
    val raw: String,
    val got: PhraseIntent?,
    val core: Boolean,
    val error: String?,
    val stats: String = "",
)

/** Battery, heat and memory readings taken around a run. */
data class Meter(val pct: Int, val chargeUah: Long, val tempC: Double, val plugged: Boolean, val pssMb: Long) {
    companion object {
        fun read(ctx: Context): Meter {
            val bm = ctx.getSystemService(BatteryManager::class.java)
            val sticky = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            return Meter(
                pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
                chargeUah = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER),
                tempC = (sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0,
                plugged = (sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0,
                pssMb = Debug.getPss() / 1024,
            )
        }
    }
}

object Bench {
    const val TAG = "A6"

    /** SPLIT = JSON answer (about 80 tokens); COMPACT = one labelled line (about 25 tokens). */
    fun systemPrompt(mode: Mode): String = mode.systemPrompt()
    val schema: String by lazy { resource(Mode.SPLIT.schema) }

    /** holdout = 64 unseen phrases, holdout2 = a second 64 (7 Nov), holdout3 and holdout4 = a third and fourth 64 (8 Nov), v1 = the original 200, quick = first 10 holdout rows. */
    fun phrases(ctx: Context, set: String): List<Phrase> {
        val file = when (set) { "v1" -> "reminder-phrases-v1.csv"; "holdout2" -> "reminder-phrases-holdout-v2.csv"; "holdout3" -> "reminder-phrases-holdout-v3.csv"; "holdout4" -> "reminder-phrases-holdout-v4.csv"; "holdout5" -> "reminder-phrases-holdout-v5.csv"; else -> "reminder-phrases-holdout-v1.csv" }
        val all = PhraseCsv.parse(ctx.assets.open("phrases/$file").bufferedReader().readLines(), file)
        return if (set == "quick") all.take(10) else all
    }

    /** The model sometimes wraps JSON in ``` fences or adds words; keep the outer object. */
    fun extractJson(raw: String): String {
        val a = raw.indexOf('{'); val b = raw.lastIndexOf('}')
        return if (a >= 0 && b > a) raw.substring(a, b + 1) else raw
    }

    suspend fun run(ctx: Context, runner: Runner, set: String, mode: Mode, log: (String) -> Unit): File {
        val system = systemPrompt(mode)
        val phrases = phrases(ctx, set)
        val before = Meter.read(ctx)
        var peakPss = before.pssMb
        val rows = mutableListOf<Row>()
        log("Running ${phrases.size} phrases on ${runner.label} (load ${runner.loadMs} ms)")
        val t0 = System.nanoTime()
        for (p in phrases) {
            currentCoroutineContext().ensureActive()
            val start = System.nanoTime()
            var raw = ""
            var error: String? = null
            try {
                raw = runner.generate(system, mode.userMessage(p.phrase))
            } catch (e: Exception) {
                error = "call:${e.javaClass.simpleName}:${e.message?.take(80)}"
            }
            val ms = (System.nanoTime() - start) / 1_000_000
            val got = if (error == null) {
                try { mode.decode(if (mode.json) extractJson(raw) else raw, p.phrase) } catch (e: Exception) { error = if (mode.json) "bad_json" else "bad_format"; null }
            } else null
            val core = got != null && Scorer.score(p.expected, got).core
            val stats = runner.lastStats()
            rows += Row(p, ms, raw, got, core, error, stats)
            peakPss = maxOf(peakPss, Debug.getPss() / 1024)
            log("#${p.id} ${if (core) "ok " else "MISS"} ${ms} ms ${error ?: ""} $stats")
        }
        val wallS = (System.nanoTime() - t0) / 1e9
        val after = Meter.read(ctx)
        return write(ctx, runner, set, rows, before, after, peakPss, wallS).also { log("Saved ${it.path}") }
    }

    private fun pctl(sorted: List<Long>, q: Double) =
        if (sorted.isEmpty()) 0 else sorted[((sorted.size - 1) * q).toInt().coerceIn(0, sorted.size - 1)]

    private fun write(
        ctx: Context, runner: Runner, set: String, rows: List<Row>,
        before: Meter, after: Meter, peakPss: Long, wallS: Double,
    ): File {
        val dir = File(ctx.getExternalFilesDir(null), "results").apply { mkdirs() }
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm"))
        val base = "A6-${runner.label}-$set-$stamp"
        val ok = rows.count { it.core }
        val lat = rows.filter { it.error == null }.map { it.ms }.sorted()
        val usedMah = (before.chargeUah - after.chargeUah) / 1000.0
        val am = ctx.getSystemService(ActivityManager::class.java)
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val soc = if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else "n/a"

        File(dir, "$base.csv").printWriter().use { w ->
            w.println("id,category,core,latency_ms,error,stats,got_intent,got_when,got_recurrence,got_duration,got_expect,raw")
            for (r in rows) {
                val g = r.got
                fun q(s: String?) = "\"" + (s ?: "").replace("\"", "\"\"").replace("\n", " ") + "\""
                w.println(listOf(r.phrase.id, r.phrase.category, r.core, r.ms, q(r.error), q(r.stats), q(g?.intent), q(g?.whenLocal),
                    q(g?.recurrence), q(g?.durationMin), q(g?.expect), q(r.raw.take(400))).joinToString(","))
            }
        }
        val md = File(dir, "$base.md")
        md.writeText(buildString {
            appendLine("# A6 on-phone AI: ${runner.label}, $set set, $stamp")
            appendLine()
            appendLine("| | |")
            appendLine("|---|---|")
            appendLine("| Phone | ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), SoC $soc |")
            appendLine("| RAM | ${mem.totalMem / 1_000_000_000.0} GB total, ${mem.availMem / 1_000_000} MB free at the end |")
            appendLine("| Model load | ${runner.loadMs} ms |")
            appendLine("| **Core accuracy** | **${"%.1f".format(ok * 100.0 / rows.size)}% ($ok/${rows.size})** |")
            appendLine("| Latency p50 / p95 / max | ${pctl(lat, 0.5)} / ${pctl(lat, 0.95)} / ${lat.lastOrNull() ?: 0} ms |")
            appendLine("| Errors | ${rows.count { it.error != null }} (${rows.mapNotNull { it.error }.groupingBy { it.substringBefore(':') }.eachCount()}) |")
            appendLine("| Wall time | ${"%.0f".format(wallS)} s |")
            rows.getOrNull(rows.size / 2)?.stats?.takeIf { it.isNotEmpty() }?.let { appendLine("| Engine timing (middle row) | $it |") }
            appendLine("| Battery | ${before.pct}% -> ${after.pct}%, ${"%.0f".format(usedMah)} mAh used, " +
                "${"%.1f".format(if (rows.isEmpty()) 0.0 else usedMah * 100 / rows.size)} mAh per 100 commands; " +
                "plugged in: ${before.plugged || after.plugged} (battery figures only count when unplugged) |")
            appendLine("| Temperature | ${before.tempC} C -> ${after.tempC} C |")
            appendLine("| App memory (PSS) | ${before.pssMb} MB at start, ${peakPss} MB peak |")
            appendLine()
            appendLine("## By category")
            appendLine("| Category | Core | Rows |")
            appendLine("|---|---|---|")
            rows.groupBy { it.phrase.category }.forEach { (c, rs) ->
                appendLine("| $c | ${"%.0f".format(rs.count { it.core } * 100.0 / rs.size)}% | ${rs.size} |")
            }
            appendLine()
            appendLine("## Misses")
            rows.filter { !it.core }.forEach { r ->
                appendLine("- #${r.phrase.id} \"${r.phrase.phrase}\": expected ${r.phrase.expected.intent} ${r.phrase.expected.whenLocal} " +
                    "${r.phrase.expected.expect}; got ${r.got?.intent} ${r.got?.whenLocal} ${r.got?.expect} ${r.error ?: ""}")
            }
        })
        return md
    }
}

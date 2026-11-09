package com.hapuya.tools.intenteval

import com.anthropic.models.messages.OutputConfig
import java.io.File
import java.time.LocalDate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

private const val USAGE = """
Usage: gradlew :tools:intent-eval:run --args="--provider <oracle|claude|alt> [options]"

  --provider   oracle (offline, no cost) | rules (no model: words only) | claude (ANTHROPIC_API_KEY) | alt (EVAL_ALT_* env vars)
  --model      Claude model id (default claude-haiku-5-5)
  --effort     Claude effort: low | medium | high (default low)
  --mode       direct (model returns the date) | split (model returns time words, code resolves) | compact (split, one-line answer) | short (split, ~650-token instructions) | short-compact (both); default direct
  --limit N    only the first N phrases
  --ids 1,5,9  only these phrase ids
  --parallel N concurrent calls (default 4)
  --max-usd X  stop sending new calls once spend reaches X (default 1.00)
  --dataset    phrase CSV (default ../05_Testing/testsets/reminder-phrases-v1.csv)
  --out        results directory (default ../05_Testing/results)
  --check      on | off: correct the model's codes against the words said (default on)

Rescore saved on-phone answers (A6 results CSV, "raw" column) with and without the check:
  gradlew :tools:intent-eval:run --args="--rescore <A6 csv> --mode short-compact --dataset <phrase csv>"
"""

fun main(args: Array<String>) {
    val opts = parseArgs(args) ?: run { println(USAGE); exitProcess(2) }
    if ("rescore" in opts) return rescore(opts)

    var phrases = PhraseCsv.load(File(opts.getOrDefault("dataset", "../05_Testing/testsets/reminder-phrases-v1.csv")))
    opts["ids"]?.let { ids -> val set = ids.split(',').map { it.trim().toInt() }.toSet(); phrases = phrases.filter { it.id in set } }
    opts["limit"]?.let { phrases = phrases.take(it.toInt()) }

    val mode = Mode.valueOf(opts.getOrDefault("mode", "direct").uppercase().replace('-', '_'))
    val check = opts.getOrDefault("check", "on") != "off"
    val provider: IntentProvider = when (opts["provider"]) {
        "oracle" -> OracleProvider()
        "rules" -> RulesProvider()
        "claude" -> ClaudeProvider(
            model = opts.getOrDefault("model", "claude-haiku-5-5"),
            effort = OutputConfig.Effort.of(opts.getOrDefault("effort", "low")),
            mode = mode,
            crossCheck = check,
        )
        "alt" -> AltProvider(mode = mode, crossCheck = check)
        else -> { println(USAGE); exitProcess(2) }
    }
    val maxUsd = opts.getOrDefault("max-usd", "1.00").toDouble()
    val parallel = opts.getOrDefault("parallel", "4").toInt()

    println("Running ${phrases.size} phrases on ${provider.label} (parallel=$parallel, budget=\$$maxUsd)")
    val results = run(provider, phrases, parallel, maxUsd)

    val outDir = File(opts.getOrDefault("out", "../05_Testing/results")).apply { mkdirs() }
    val stem = "A2-${provider.label.replace(Regex("[^A-Za-z0-9.-]+"), "_")}-${LocalDate.now()}"
    val report = Report(provider.label, results)
    File(outDir, "$stem.csv").writeText(report.rowsCsv())
    File(outDir, "$stem.md").writeText(report.summaryMarkdown())
    println(report.headline())
    if (results.any { it.call.error == "skipped:auth" }) println("Stopped early: the provider rejected the API key (401).")
    println("Wrote ${File(outDir, "$stem.md").path} and .csv")
}

/** One scored phrase. [score] is null when the call failed or was skipped. */
data class Outcome(val phrase: Phrase, val call: CallResult, val score: RowScore?)

private fun run(provider: IntentProvider, phrases: List<Phrase>, parallel: Int, maxUsd: Double): List<Outcome> {
    val spent = java.util.concurrent.atomic.DoubleAdder()
    val authFailed = java.util.concurrent.atomic.AtomicBoolean(false)
    val pool = Executors.newFixedThreadPool(parallel)
    val futures = phrases.map { p ->
        pool.submit<Outcome> {
            if (spent.sum() >= maxUsd) return@submit Outcome(p, CallResult(null, 0, error = "skipped:budget"), null)
            // A bad or missing key fails every call; stop after the first one.
            if (authFailed.get()) return@submit Outcome(p, CallResult(null, 0, error = "skipped:auth"), null)
            val call = provider.parse(p)
            if (call.error == "api_error:401" || call.error == "http_401") authFailed.set(true)
            spent.add(call.costUsd)
            Outcome(p, call, call.intent?.let { Scorer.score(p.expected, it) })
        }
    }
    pool.shutdown()
    pool.awaitTermination(1, TimeUnit.HOURS)
    return futures.map { it.get() }
}

private fun parseArgs(args: Array<String>): Map<String, String>? {
    val out = mutableMapOf<String, String>()
    var i = 0
    while (i < args.size) {
        val k = args[i].removePrefix("--")
        if (!args[i].startsWith("--") || i + 1 >= args.size) return null
        out[k] = args[i + 1]
        i += 2
    }
    return out.takeIf { "provider" in it || "rescore" in it }
}

/** Decodes the saved raw answers of an on-phone run again, with and without [CrossCheck]. */
private fun rescore(opts: Map<String, String>) {
    val mode = Mode.valueOf(opts.getOrDefault("mode", "short-compact").uppercase().replace('-', '_'))
    val phrases = PhraseCsv.load(File(opts.getValue("dataset"))).associateBy { it.id }
    val lines = File(opts.getValue("rescore")).readLines().filter { it.isNotBlank() }
    val header = PhraseCsv.splitRow(lines.first())
    val idCol = header.indexOf("id"); val rawCol = header.indexOf("raw")
    require(idCol >= 0 && rawCol >= 0) { "Need id and raw columns" }
    var before = 0; var after = 0; var n = 0
    for (line in lines.drop(1)) {
        val f = PhraseCsv.splitRow(line)
        val p = phrases[f[idCol].toInt()] ?: continue
        val raw = f[rawCol]
        fun ok(said: String) = runCatching { Scorer.score(p.expected, mode.decode(raw, said)).core }.getOrDefault(false)
        val b = ok(""); val a = ok(p.phrase)
        n++; if (b) before++; if (a) after++
        if (a != b || !a) {
            val got = runCatching { mode.decode(raw, p.phrase) }.getOrNull()
            println("#${p.id} ${if (b) "ok " else "MISS"} -> ${if (a) "ok " else "MISS"}  ${p.phrase}\n      raw: $raw\n      got: ${got?.let { "${it.intent} ${it.whenLocal} ${it.recurrence} ${it.durationMin} ${it.expect}" }}" +
                "  want: ${p.expected.intent} ${p.expected.whenLocal} ${p.expected.recurrence} ${p.expected.durationMin} ${p.expected.expect}")
        }
    }
    println("Without check: $before/$n (${"%.1f".format(before * 100.0 / n)}%)  With check: $after/$n (${"%.1f".format(after * 100.0 / n)}%)")
}

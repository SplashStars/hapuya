package com.hapuya.tools.intenteval

import com.anthropic.models.messages.OutputConfig
import java.io.File
import java.time.LocalDate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

private const val USAGE = """
Usage: gradlew :tools:intent-eval:run --args="--provider <oracle|claude|alt> [options]"

  --provider   oracle (offline, no cost) | claude (ANTHROPIC_API_KEY) | alt (EVAL_ALT_* env vars)
  --model      Claude model id (default claude-haiku-5-5)
  --effort     Claude effort: low | medium | high (default low)
  --limit N    only the first N phrases
  --ids 1,5,9  only these phrase ids
  --parallel N concurrent calls (default 4)
  --max-usd X  stop sending new calls once spend reaches X (default 1.00)
  --dataset    phrase CSV (default ../05_Testing/testsets/reminder-phrases-v1.csv)
  --out        results directory (default ../05_Testing/results)
"""

fun main(args: Array<String>) {
    val opts = parseArgs(args) ?: run { println(USAGE); exitProcess(2) }

    var phrases = PhraseCsv.load(File(opts.getOrDefault("dataset", "../05_Testing/testsets/reminder-phrases-v1.csv")))
    opts["ids"]?.let { ids -> val set = ids.split(',').map { it.trim().toInt() }.toSet(); phrases = phrases.filter { it.id in set } }
    opts["limit"]?.let { phrases = phrases.take(it.toInt()) }

    val provider: IntentProvider = when (opts["provider"]) {
        "oracle" -> OracleProvider()
        "claude" -> ClaudeProvider(
            model = opts.getOrDefault("model", "claude-haiku-5-5"),
            effort = OutputConfig.Effort.of(opts.getOrDefault("effort", "low")),
        )
        "alt" -> AltProvider()
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
    return out.takeIf { "provider" in it }
}

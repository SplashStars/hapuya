package com.hapuya.tools.intenteval

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class Report(private val label: String, private val outcomes: List<Outcome>) {
    private val scored = outcomes.filter { it.score != null }
    private val total = outcomes.size

    private fun pct(n: Int, of: Int = total) = if (of == 0) "n/a" else "%.1f%%".format(100.0 * n / of)

    fun headline(): String {
        val core = scored.count { it.score!!.core }
        return "$label: core ${pct(core)} ($core/$total), full ${pct(scored.count { it.score!!.full })}, " +
            "errors ${outcomes.count { it.score == null }}, cost \$${"%.4f".format(cost())}, " +
            "p50 ${percentile(50)} ms, p95 ${percentile(95)} ms"
    }

    fun summaryMarkdown(): String = buildString {
        appendLine("# A2 intent eval: $label")
        appendLine()
        appendLine("Run ${LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME).take(16)}. ${total} phrases; reference clock ${Reference.DATETIME} ${Reference.ZONE}.")
        appendLine()
        appendLine("| Metric | Value |")
        appendLine("|---|---|")
        appendLine("| **Core accuracy** (intent, time, recurrence, duration, act/clarify/refuse) | ${pct(scored.count { it.score!!.core })} |")
        appendLine("| Full accuracy (core + title) | ${pct(scored.count { it.score!!.full })} |")
        appendLine("| PRD F5 bar | 95% core on this set |")
        appendLine("| Errors / refusals / skipped | ${outcomes.count { it.score == null }} |")
        appendLine("| Latency p50 / p95 | ${percentile(50)} ms / ${percentile(95)} ms |")
        appendLine("| Tokens in / out | ${outcomes.sumOf { it.call.inputTokens }} / ${outcomes.sumOf { it.call.outputTokens }} |")
        appendLine("| Cost (whole run) | \$${"%.4f".format(cost())} |")
        appendLine("| Cost per phrase | \$${"%.5f".format(if (total == 0) 0.0 else cost() / total)} |")
        appendLine()
        appendLine("## By category (core accuracy)")
        appendLine()
        appendLine("| Category | Core | Rows |")
        appendLine("|---|---|---|")
        outcomes.groupBy { it.phrase.category }.forEach { (cat, rows) ->
            appendLine("| $cat | ${pct(rows.count { it.score?.core == true }, rows.size)} | ${rows.size} |")
        }
        appendLine()
        appendLine("## Field error counts")
        appendLine()
        appendLine("| intent | when_local | recurrence | duration | expect category | title |")
        appendLine("|---|---|---|---|---|---|")
        appendLine("| ${miss { it.intent }} | ${miss { it.whenLocal }} | ${miss { it.recurrence }} | ${miss { it.duration }} | ${miss { it.expectCategory }} | ${miss { it.title }} |")
        appendLine()
        val failures = outcomes.filter { it.score?.core != true }
        if (failures.isNotEmpty()) {
            appendLine("## Core failures (${failures.size})")
            appendLine()
            appendLine("| id | phrase | expected | got |")
            appendLine("|---|---|---|---|")
            failures.forEach { o ->
                val got = o.call.intent?.let(::brief) ?: (o.call.error ?: "no result")
                appendLine("| ${o.phrase.id} | ${o.phrase.phrase.md()} | ${brief(o.phrase.expected).md()} | ${got.md()} |")
            }
        }
    }

    fun rowsCsv(): String = buildString {
        appendLine("id,category,core,full,latency_ms,input_tokens,output_tokens,cost_usd,error,got_intent,got_when,got_recurrence,got_duration,got_expect,got_title")
        outcomes.sortedBy { it.phrase.id }.forEach { o ->
            val g = o.call.intent
            appendLine(listOf(
                o.phrase.id, o.phrase.category, o.score?.core ?: false, o.score?.full ?: false,
                o.call.latencyMs, o.call.inputTokens, o.call.outputTokens, "%.6f".format(o.call.costUsd),
                o.call.error.orEmpty(), g?.intent.orEmpty(), g?.whenLocal.orEmpty(), g?.recurrence.orEmpty(),
                g?.durationMin.orEmpty(), g?.expect.orEmpty(), g?.title.orEmpty(),
            ).joinToString(",") { csv(it.toString()) })
        }
    }

    private fun miss(field: (RowScore) -> Boolean) = scored.count { !field(it.score!!) }
    private fun cost() = outcomes.sumOf { it.call.costUsd }

    private fun percentile(p: Int): Long {
        val xs = outcomes.filter { it.call.intent != null }.map { it.call.latencyMs }.sorted()
        if (xs.isEmpty()) return 0
        return xs[((p / 100.0) * (xs.size - 1)).toInt()]
    }

    private fun brief(i: Intent) =
        listOf(i.intent, i.whenLocal, i.recurrence, i.durationMin, i.expect).filter { it.isNotBlank() }.joinToString(" · ")

    private fun String.md() = replace("|", "\\|")
    private fun csv(s: String) = if (s.any { it == ',' || it == '"' || it == '\n' }) "\"${s.replace("\"", "\"\"")}\"" else s
}

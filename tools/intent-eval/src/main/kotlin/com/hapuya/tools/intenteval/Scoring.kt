package com.hapuya.tools.intenteval

/**
 * Field-by-field comparison per testsets/README.md: intent, when_local,
 * recurrence and duration_min must match exactly; the expect *category*
 * (act / clarify / unsupported / block) must match, while its reason label is
 * reported but free; titles are compared loosely.
 */
data class RowScore(
    val intent: Boolean,
    val whenLocal: Boolean,
    val recurrence: Boolean,
    val duration: Boolean,
    val expectCategory: Boolean,
    val title: Boolean,
) {
    /** Everything the app acts on. A wrong title is visible to the user but harmless. */
    val core get() = intent && whenLocal && recurrence && duration && expectCategory
    val full get() = core && title
}

object Scorer {
    fun score(expected: Intent, actual: Intent): RowScore = RowScore(
        intent = expected.intent == actual.intent.trim(),
        whenLocal = expected.whenLocal == actual.whenLocal.trim(),
        recurrence = normaliseRrule(expected.recurrence) == normaliseRrule(actual.recurrence),
        duration = sameNumber(expected.durationMin, actual.durationMin),
        expectCategory = category(expected.expect) == category(actual.expect),
        title = titleMatches(expected.title, actual.title),
    )

    fun category(expect: String) = expect.trim().substringBefore(':').lowercase()

    /** Order-insensitive within BYDAY and across parts; tolerates an "RRULE:" prefix. */
    fun normaliseRrule(rule: String): String =
        rule.trim().removePrefix("RRULE:").split(';').filter { it.isNotBlank() }
            .map { part ->
                val (k, v) = part.split('=', limit = 2).let { it[0].uppercase() to it.getOrElse(1) { "" }.uppercase() }
                if (k == "BYDAY") "$k=${v.split(',').sorted().joinToString(",")}" else "$k=$v"
            }
            .sorted().joinToString(";")

    fun sameNumber(a: String, b: String): Boolean {
        if (a.isBlank() && b.isBlank()) return true
        val x = a.trim().toDoubleOrNull() ?: return false
        val y = b.trim().toDoubleOrNull() ?: return false
        return kotlin.math.abs(x - y) < 1e-6
    }

    /**
     * Loose title check: both empty, one contains the other, or at least half of
     * the meaningful words overlap. Mismatches are listed in the report for a
     * human to judge, so this only needs to be good, not perfect.
     */
    fun titleMatches(expected: String, actual: String): Boolean {
        val e = words(expected)
        val a = words(actual)
        if (e.isEmpty()) return true // no expected title (lists, refusals): any title is fine
        if (a.isEmpty()) return false
        val ej = e.joinToString(" ")
        val aj = a.joinToString(" ")
        if (ej in aj || aj in ej) return true
        val overlap = e.intersect(a.toSet()).size.toDouble()
        return overlap / e.size >= 0.5
    }

    private val STOP = setOf("a", "an", "the", "to", "my", "for", "of", "with", "and", "about", "on", "in", "at")

    private fun words(s: String) =
        s.lowercase().replace(Regex("[^a-z0-9' ]"), " ").split(' ')
            .filter { it.isNotBlank() && it !in STOP }
}

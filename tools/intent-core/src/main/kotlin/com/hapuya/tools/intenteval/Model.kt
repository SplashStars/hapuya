package com.hapuya.tools.intenteval

import java.io.File

/** One row of `05_Testing/testsets/reminder-phrases-v1.csv`. */
data class Phrase(
    val id: Int,
    val category: String,
    val phrase: String,
    val expected: Intent,
)

/** The structured intent, as the model returns it and as the test set expects it. */
data class Intent(
    val intent: String,
    val title: String,
    val whenLocal: String,
    val recurrence: String,
    val durationMin: String,
    val expect: String,
)

/** Fixed clock and locale the phrase set was written against (testsets/README.md). */
object Reference {
    const val DATETIME = "Monday 2026-10-26 09:00"
    const val ZONE = "America/New_York"
    const val LOCALE = "en-US"
    val NOW: java.time.LocalDateTime = java.time.LocalDateTime.of(2026, 10, 26, 9, 0)

    fun userMessage(phrase: String) =
        "Reference time: $DATETIME\nTime zone: $ZONE\nLocale: $LOCALE\nCommand: $phrase"
}

object PhraseCsv {
    private val HEADER = listOf("id", "category", "phrase", "intent", "title", "when_local", "recurrence", "duration_min", "expect")

    fun load(file: File): List<Phrase> = parse(file.readLines(), file.toString())

    /** Same as [load] for text that is not a file (an Android asset, for example). */
    fun parse(allLines: List<String>, source: String = "phrase set"): List<Phrase> {
        val lines = allLines.filter { it.isNotBlank() }
        require(splitRow(lines.first()) == HEADER) { "Unexpected header in $source" }
        return lines.drop(1).map { line ->
            val f = splitRow(line)
            require(f.size == HEADER.size) { "Bad row (${f.size} fields): $line" }
            Phrase(
                id = f[0].toInt(),
                category = f[1],
                phrase = f[2],
                expected = Intent(f[3], f[4], f[5], f[6], f[7], f[8]),
            )
        }
    }

    /** Minimal RFC 4180 split: commas, double-quoted fields, doubled quotes. */
    fun splitRow(line: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                quoted && c == '"' && line.getOrNull(i + 1) == '"' -> { cur.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> { out += cur.toString(); cur.clear() }
                else -> cur.append(c)
            }
            i++
        }
        out += cur.toString()
        return out
    }
}

/** Loads a classpath resource bundled with this tool. */
fun resource(name: String): String =
    requireNotNull(Phrase::class.java.getResource("/$name")) { "Missing resource $name" }.readText()

/**
 * direct: the model returns when_local itself. split: the model returns the time
 * words in a fixed code ([SplitIntent]) and [Resolver] computes the date.
 */
enum class Mode(val prompt: String, val schema: String, val suffix: String) {
    DIRECT("intent-system-prompt.md", "intent-schema.json", ""),
    SPLIT("intent-split-prompt.md", "intent-split-schema.json", "-split"),

    /** Split mode with a one-line "a|b|c" answer: about 20 output tokens instead of 80 (A6 speed). */
    COMPACT("intent-split-prompt.md", "", "-compact"),

    /** Split mode with about 350 tokens of instructions instead of about 1,700 (A6: fresh read in ~3 s). */
    SHORT("intent-short-prompt.md", "", "-short"),

    /** SHORT instructions with the COMPACT one-line answer: least reading and least writing. */
    SHORT_COMPACT("intent-short-prompt.md", "", "-short-compact");

    /** True when the answer is a JSON object (so JSON mode / a schema applies). */
    val json get() = this != COMPACT && this != SHORT_COMPACT

    /** The full instructions for a model that is told the format in the prompt (not by an API schema). */
    fun systemPrompt(): String = when (this) {
        COMPACT -> resource(prompt).replace(
            "Return exactly one JSON object matching the schema.", "Answer in the one-line format given at the end.",
        ) + resource("intent-compact-format.md")
        SHORT -> resource(prompt)
        SHORT_COMPACT -> resource(prompt).lines()
            .filterNot { it.startsWith("Example:") || it.startsWith("Answer with the JSON") }
            .joinToString("\n")
            .replace("into JSON for", "into one line for")
            .replace("Always give all 8 keys as strings, \"\" when not said:", "Fields (codes as below):") +
            "\n" + resource("intent-compact-format.md")
        else -> resource(prompt) + "\nReturn only a JSON object that matches this JSON Schema:\n" + resource(schema)
    }

    /** Split modes leave out the date and weekday so the model has nothing to do date maths with. */
    fun userMessage(phrase: String): String = when (this) {
        DIRECT -> Reference.userMessage(phrase)
        SPLIT, COMPACT, SHORT, SHORT_COMPACT -> "Locale: ${Reference.LOCALE}\nCommand: $phrase"
    }

    /**
     * [said] is the user's command: when given, [CrossCheck] corrects codes that do not
     * match the words said (pass "" to turn the check off, as the A6 "before" runs did).
     */
    fun decode(text: String, said: String = ""): Intent = when (this) {
        DIRECT -> intentFromJson(text)
        SPLIT, SHORT -> checked(splitFromJson(text), said)
        COMPACT, SHORT_COMPACT -> checked(splitFromLine(text), said)
    }

    private fun checked(s: SplitIntent, said: String) = Resolver.resolve(CrossCheck.check(Resolver.tidy(s), said))
}

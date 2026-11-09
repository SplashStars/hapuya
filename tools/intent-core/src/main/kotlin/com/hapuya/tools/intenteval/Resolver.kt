package com.hapuya.tools.intenteval

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * Split mode: the model reports the time words it heard ([day], [clock], [until])
 * in a small fixed grammar (see intent-split-prompt.md), and [Resolver] turns them
 * into a date with the product's time rules. Date maths lives in code, not the model.
 */
data class SplitIntent(
    val intent: String,
    val title: String,
    val day: String,
    val clock: String,
    val until: String,
    val recurrence: String,
    val durationMin: String,
    val expect: String,
)

fun splitFromJson(text: String): SplitIntent {
    val n = com.fasterxml.jackson.databind.ObjectMapper().readTree(text)
    fun f(name: String) = n.path(name).let { if (it.isMissingNode || it.isNull) "" else it.asText().trim() }
    return SplitIntent(f("intent"), f("title"), f("day"), f("clock"), f("until"), f("recurrence"), f("duration_min"), f("expect"))
}

/**
 * Compact mode: one line "intent | title:... | day:... | clock:...", with empty fields
 * left out and expect defaulting to "act" (see intent-compact-format.md). Labelled
 * fields, because a small model miscounts empty positional fields ("a||||b"): tried
 * 7 Nov 2026, 32.8% on the holdout. Takes the first line that starts with an intent
 * name (skipping a code fence); unknown labels are ignored. Throws
 * [IllegalArgumentException] if there is no such line.
 */
private val DAY_KINDS = setOf("in", "weekday", "next", "date", "dom", "holiday")
private val OUTCOMES = setOf("clarify", "unsupported", "block")
private val BARE_DAYS = setOf("today", "tomorrow", "yesterday", "weekend", "vague")

fun splitFromLine(text: String): SplitIntent {
    val intentName = Regex("[a-z]+(\\.[a-z]+)?")
    val line = text.lineSequence()
        .map { it.trim().trim('`').trim() }
        .firstOrNull { it.substringBefore('|').trim().matches(intentName) && it.substringBefore('|').trim() != "intent" }
        ?: throw IllegalArgumentException("no compact line")
    val parts = line.split('|').map { it.trim() }
    val f = mutableMapOf<String, String>()
    for (p in parts.drop(1)) {
        val k = p.substringBefore(':', "").trim().lowercase()
        val v = p.substringAfter(':').trim().removeSurrounding("\"")
        when {
            // A day or clock code written without its label ("| in:3:hour", "| tomorrow").
            k in DAY_KINDS -> f.putIfAbsent("day", "$k:$v")
            k == "part" || k == "bare" -> f.putIfAbsent("clock", "$k:$v")
            // An outcome written without its label ("| unsupported:location") beats "act".
            k in OUTCOMES -> if (f["expect"].isNullOrEmpty() || f["expect"] == "act") f["expect"] = "$k:$v"
            k.isEmpty() && p.trim().lowercase() in BARE_DAYS -> f.putIfAbsent("day", p.trim().lowercase())
            k.isNotEmpty() -> f.putIfAbsent(k, v)
        }
    }
    fun at(k: String) = f[k].orEmpty()
    return SplitIntent(
        parts[0], at("title"), at("day"), at("clock"), at("until"), at("recurrence"), at("duration_min"),
        at("expect").ifEmpty { "act" },
    )
}

object Resolver {
    private val PARTS = mapOf(
        "morning" to LocalTime.of(9, 0), "noon" to LocalTime.NOON, "lunch" to LocalTime.NOON,
        "afternoon" to LocalTime.of(15, 0), "eod" to LocalTime.of(17, 0), "evening" to LocalTime.of(18, 0),
        "tonight" to LocalTime.of(20, 0), "night" to LocalTime.of(20, 0),
    )
    private val PM_PARTS = setOf("afternoon", "eod", "evening", "tonight", "night")
    private val DAYS = mapOf(
        "MO" to DayOfWeek.MONDAY, "TU" to DayOfWeek.TUESDAY, "WE" to DayOfWeek.WEDNESDAY, "TH" to DayOfWeek.THURSDAY,
        "FR" to DayOfWeek.FRIDAY, "SA" to DayOfWeek.SATURDAY, "SU" to DayOfWeek.SUNDAY,
    )
    private val EDITS = setOf(
        "reminder.delete", "reminder.move", "reminder.list", "event.delete", "event.move", "event.update", "event.list",
    )
    /** Intents whose duration_min means something; everything else drops it. */
    private val HAS_DURATION = setOf("event.create", "timer.set", "reminder.snooze")

    sealed interface Clock {
        val time: LocalTime? get() = null
        data class Fixed(override val time: LocalTime) : Clock
        /**
         * A bare hour mapped by rule 1 (7-11 am, 12 noon, 1-6 pm); a one-off reminder must
         * ask if it has passed. Alarms read the hour as said ("6:30 tomorrow" = 06:30): [asSaid].
         */
        data class Bare(override val time: LocalTime, val asSaid: LocalTime) : Clock
        /** A day-part word alone; rolls to tomorrow if it has passed. */
        data class Part(override val time: LocalTime) : Clock
        data object Invalid : Clock
        data object None : Clock
    }

    private val HM = Regex("""(\d{1,2})(?::(\d{2}))?""")

    fun parseClock(s: String): Clock {
        val c = s.trim().lowercase()
        if (c.isEmpty()) return Clock.None
        HM.matchEntire(c)?.let { m ->
            val h = m.groupValues[1].toInt(); val min = m.groupValues[2].ifEmpty { "0" }.toInt()
            return if (h > 23 || min > 59) Clock.Invalid else Clock.Fixed(LocalTime.of(h, min))
        }
        if (c.startsWith("bare:")) {
            val m = HM.matchEntire(c.removePrefix("bare:")) ?: return Clock.Invalid
            val h = m.groupValues[1].toInt(); val min = m.groupValues[2].ifEmpty { "0" }.toInt()
            if (h !in 1..12 || min > 59) return Clock.Invalid
            val h24 = if (h in 1..6) h + 12 else h // 7-11 am, 12 noon, 1-6 pm
            return Clock.Bare(LocalTime.of(h24, min), LocalTime.of(h % 12 + if (h == 12) 12 else 0, min))
        }
        if (c.startsWith("part:")) {
            val (part, at) = c.removePrefix("part:").split('@', limit = 2).let { it[0] to it.getOrNull(1) }
            val default = PARTS[part] ?: return Clock.Invalid
            if (at == null) return Clock.Part(default)
            val m = HM.matchEntire(at) ?: return Clock.Invalid
            var h = m.groupValues[1].toInt(); val min = m.groupValues[2].ifEmpty { "0" }.toInt()
            if (h > 23 || min > 59) return Clock.Invalid
            if (part in PM_PARTS && h in 1..11) h += 12
            if (part == "morning" && h == 12) h = 0
            return Clock.Fixed(LocalTime.of(h, min))
        }
        return Clock.Invalid
    }

    private sealed interface Day {
        data object None : Day
        data class On(val date: LocalDate) : Day
        /** "in 90 minutes": an exact moment. */
        data class Moment(val at: LocalDateTime) : Day
        /** "in 3 days": a date that keeps the current time unless a clock is given. */
        data class Shifted(val at: LocalDateTime) : Day
        data class Ask(val what: String) : Day
    }

    private fun parseDay(s: String, now: LocalDateTime): Day {
        val today = now.toLocalDate()
        val d = s.trim().lowercase()
        if (d.isEmpty()) return Day.None
        val (kind, arg) = d.split(':', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        return when (kind) {
            "today" -> Day.On(today)
            "tomorrow" -> Day.On(today.plusDays(1))
            "yesterday" -> Day.Ask("past-time")
            "weekday" -> DAYS[arg.uppercase()]?.let { Day.On(today.with(TemporalAdjusters.next(it))) } ?: Day.Ask("when")
            "next" -> Day.Ask("which-day")
            "date" -> parseDate(arg, today)
            "dom" -> {
                val parts = arg.split(':')
                val dom = parts[0].toIntOrNull() ?: return Day.Ask("when")
                val dow = parts.getOrNull(1)?.let { DAYS[it.uppercase()] }
                generateSequence(today) { it.plusDays(1) }.take(800)
                    .firstOrNull { it.dayOfMonth == dom && (dow == null || it.dayOfWeek == dow) }
                    ?.let { Day.On(it) } ?: Day.Ask("which-date")
            }
            "holiday" -> holiday(arg, today)?.let { Day.On(it) } ?: Day.Ask("which-date")
            "in" -> {
                val (nStr, unit) = arg.split(':', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                val n = nStr.toLongOrNull() ?: return Day.Ask("when")
                when (unit.removeSuffix("s")) {
                    "minute", "min" -> Day.Moment(now.plusMinutes(n))
                    "hour" -> Day.Moment(now.plusHours(n))
                    "day" -> Day.Shifted(now.plusDays(n))
                    "week" -> Day.Shifted(now.plusWeeks(n))
                    "month" -> Day.Shifted(now.plusMonths(n))
                    "year" -> Day.Shifted(now.plusYears(n))
                    else -> Day.Ask("when")
                }
            }
            else -> Day.Ask("when") // "weekend", "vague" and anything outside the grammar
        }
    }

    private fun parseDate(arg: String, today: LocalDate): Day {
        val p = arg.split('/').map { it.toIntOrNull() ?: return Day.Ask("invalid-date") }
        if (p.size !in 2..3) return Day.Ask("invalid-date")
        val (m, d) = p
        return runCatching {
            if (p.size == 3) {
                val date = LocalDate.of(p[2], m, d)
                if (date < today) Day.Ask("past-time") else Day.On(date)
            } else {
                val date = LocalDate.of(today.year, m, d)
                Day.On(if (date < today) date.plusYears(1) else date)
            }
        }.getOrElse { Day.Ask("invalid-date") }
    }

    private fun holiday(name: String, today: LocalDate): LocalDate? {
        fun on(year: Int): LocalDate? = when (name) {
            "thanksgiving" -> LocalDate.of(year, 11, 1).with(TemporalAdjusters.dayOfWeekInMonth(4, DayOfWeek.THURSDAY))
            "christmas" -> LocalDate.of(year, 12, 25)
            "christmas-eve" -> LocalDate.of(year, 12, 24)
            "new-years-eve" -> LocalDate.of(year, 12, 31)
            "new-year" -> LocalDate.of(year, 1, 1)
            "halloween" -> LocalDate.of(year, 10, 31)
            "valentines" -> LocalDate.of(year, 2, 14)
            "independence-day" -> LocalDate.of(year, 7, 4)
            else -> null
        }
        val d = on(today.year) ?: return null
        return if (d < today) on(today.year + 1) else d
    }

    /** First occurrence of [rule] strictly after [now]; interval-only rules start one interval out. */
    fun firstOccurrence(rule: String, time: LocalTime?, now: LocalDateTime): LocalDateTime? {
        val parts = rule.removePrefix("RRULE:").split(';').filter { it.contains('=') }
            .associate { it.substringBefore('=').uppercase() to it.substringAfter('=').uppercase() }
        val freq = parts["FREQ"] ?: return null
        val interval = parts["INTERVAL"]?.toLongOrNull() ?: 1
        val byDay = parts["BYDAY"]?.split(',')?.mapNotNull { DAYS[it.takeLast(2)] }.orEmpty()
        val byMonthDay = parts["BYMONTHDAY"]?.split(',')?.mapNotNull { it.toIntOrNull() }.orEmpty()
        val byMonth = parts["BYMONTH"]?.split(',')?.mapNotNull { it.toIntOrNull() }.orEmpty()
        val noBy = byDay.isEmpty() && byMonthDay.isEmpty() && byMonth.isEmpty()
        val today = now.toLocalDate()

        if (freq == "HOURLY") return (time?.let { today.atTime(it) } ?: now.truncatedTo(ChronoUnit.HOURS)).plusHours(interval)
        if (freq == "MINUTELY") return now.plusMinutes(interval)
        if (noBy && interval > 1) {
            val unit = when (freq) { "DAILY" -> ChronoUnit.DAYS; "WEEKLY" -> ChronoUnit.WEEKS; "MONTHLY" -> ChronoUnit.MONTHS; else -> ChronoUnit.YEARS }
            return today.plus(interval, unit).atTime(time ?: now.toLocalTime())
        }
        val t = time ?: LocalTime.of(9, 0)
        fun matches(d: LocalDate): Boolean {
            if (byMonth.isNotEmpty() && d.monthValue !in byMonth) return false
            if (byMonthDay.isNotEmpty() && byMonthDay.none { it == d.dayOfMonth || (it < 0 && d.lengthOfMonth() + it + 1 == d.dayOfMonth) }) return false
            if (byDay.isNotEmpty() && d.dayOfWeek !in byDay) return false
            return when (freq) {
                "WEEKLY" -> byDay.isNotEmpty() || d.dayOfWeek == today.dayOfWeek
                "MONTHLY" -> byDay.isNotEmpty() || byMonthDay.isNotEmpty() || d.dayOfMonth == today.dayOfMonth
                "YEARLY" -> !noBy || (d.monthValue == today.monthValue && d.dayOfMonth == today.dayOfMonth)
                else -> true
            }
        }
        return generateSequence(today) { it.plusDays(1) }.take(800)
            .filter(::matches).map { it.atTime(t) }.firstOrNull { it > now }
    }

    private fun fmt(dt: LocalDateTime) = dt.toString().take(16)

    private val HOLIDAY_ALIASES = mapOf(
        "new-year-eve" to "new-years-eve", "newyearseve" to "new-years-eve", "nye" to "new-years-eve",
        "new-years" to "new-year", "new-years-day" to "new-year", "new-year-day" to "new-year",
        "christmas-day" to "christmas", "xmas" to "christmas", "xmas-eve" to "christmas-eve",
        "valentines-day" to "valentines", "valentine" to "valentines", "valentine-day" to "valentines",
        "july-4" to "independence-day", "fourth-of-july" to "independence-day", "thanksgiving-day" to "thanksgiving",
    )
    private val BY_TIME = Regex("^BY(HOUR|MINUTE|SECOND)=", RegexOption.IGNORE_CASE)

    /**
     * Fixes small-model slips that have only one reading (seen on Gemma 4 E2B with the
     * short instructions, 7 Nov 2026): a day-part without "part:" ("evening"), a
     * day-part glued to a holiday ("holiday:christmas-morning"), holiday spellings,
     * times inside the RRULE (BYHOUR=18; the time belongs in clock), a one-off "in a
     * week" also written as a rule, a rule in the day field, "date:30" for a day of the
     * month, full weekday names, and "booking.create" or "snooze" for the intent. Anything with
     * more than one reading is left alone, so the rules still ask.
     */
    fun tidy(s: SplitIntent): SplitIntent {
        val intent = s.intent.trim().lowercase().let {
            when {
                it.startsWith("booking") -> "booking"
                it.startsWith("payment") -> "payment"
                it == "snooze" -> "reminder.snooze"
                else -> it
            }
        }
        fun part(c: String) = c.trim().let { if (it.lowercase().substringBefore('@') in PARTS) "part:${it.lowercase()}" else it }
        var day = part(s.day)
        var clock = part(s.clock)
        var recurrence = s.recurrence
        // A rule written in the day field ("day:FREQ=WEEKLY;BYDAY=MO").
        if (day.uppercase().startsWith("FREQ=")) {
            if (recurrence.isBlank()) recurrence = day
            day = ""
        }
        // "date:30" has no month: it is a day of the month.
        Regex("date:(\\d{1,2})", RegexOption.IGNORE_CASE).matchEntire(day)?.let { day = "dom:" + it.groupValues[1] }
        // "weekday:saturday" -> "weekday:SA".
        Regex("weekday:([a-z]{3,})", RegexOption.IGNORE_CASE).matchEntire(day)?.let { day = "weekday:" + it.groupValues[1].take(2).uppercase() }
        if (day.lowercase().startsWith("holiday:")) {
            var name = day.substring(8).lowercase().replace("'", "").replace("’", "").replace(' ', '-')
            Regex("(.+)-(morning|afternoon|evening|night)").matchEntire(name)?.let { m ->
                name = m.groupValues[1]
                if (clock.isBlank()) clock = "part:" + m.groupValues[2]
            }
            day = "holiday:" + (HOLIDAY_ALIASES[name] ?: name)
        }
        var rule = recurrence.trim().removePrefix("RRULE:").split(';').filter { it.isNotBlank() && !BY_TIME.containsMatchIn(it.trim()) }
        // "BYDAY=SA;BYDAY=SU" -> "BYDAY=SA,SU": a repeated key is one list.
        rule = rule.groupBy { it.substringBefore('=').trim().uppercase() }.map { (k, parts) ->
            if (parts.size == 1) parts[0] else "$k=" + parts.joinToString(",") { it.substringAfter('=').trim() }
        }
        // "in a week" is a one-off: a rule with no BY part next to an "in:" day is the offset restated.
        if (day.lowercase().startsWith("in:") && rule.none { it.trim().uppercase().startsWith("BY") }) rule = emptyList()
        return s.copy(intent = intent, day = day, clock = clock, recurrence = rule.joinToString(";"))
    }

    fun resolve(raw: SplitIntent, now: LocalDateTime = Reference.NOW): Intent {
        val s = tidy(raw)
        val keepDuration = s.intent in HAS_DURATION
        fun out(whenLocal: String = "", rec: String = s.recurrence, dur: String = s.durationMin, expect: String = s.expect) =
            Intent(s.intent, s.title, whenLocal, rec, if (keepDuration) dur else "", expect)

        // Policy is the app's job, not the model's: money is always blocked and
        // bookings are always unsupported in MVP, whatever the model said.
        if (s.intent == "payment") return out(rec = "", dur = "", expect = "block:money")
        if (s.intent == "booking") return out(rec = "", dur = "", expect = "unsupported:commerce")
        when (Scorer.category(s.expect)) {
            "block", "unsupported" -> return out(rec = "", dur = "")
            "clarify" -> return out()
        }
        // Small models sometimes put a day-part word in the day field ("day": "part:tonight").
        // A day-part means today unless a day was also given, so move it to the clock.
        val partInDay = s.day.trim().lowercase().let { it.startsWith("part:") || it in PARTS || it == "this-morning" }
        // ...and the reverse: a relative code ("in:20:minute") in the clock field.
        val relInClock = s.clock.trim().lowercase().startsWith("in:")
        val dayCode = when {
            relInClock && (s.day.isBlank() || partInDay) -> s.clock
            partInDay -> ""
            else -> s.day
        }
        val clockCode = when {
            relInClock -> if (partInDay) s.day else ""
            !partInDay || s.clock.isNotBlank() -> s.clock
            s.day.trim().lowercase().startsWith("part:") -> s.day
            else -> "part:" + s.day.trim().lowercase().removePrefix("this-")
        }
        val clock = parseClock(clockCode)
        if (clock is Clock.Invalid) return out(rec = "", dur = "", expect = "clarify:invalid-time")
        if (s.intent == "timer.set" || s.intent == "reminder.snooze") return out(rec = "")

        val isEdit = s.intent in EDITS
        val isAlarm = s.intent.startsWith("alarm")
        val isEvent = s.intent == "event.create"
        val time = if (isAlarm && clock is Clock.Bare) clock.asSaid else clock.time
        val until = parseClock(s.until).time

        fun eventDuration(timed: Boolean): String = when {
            !isEvent || !timed -> ""
            until != null && time != null && until > time -> ChronoUnit.MINUTES.between(time, until).toString()
            s.durationMin.isNotBlank() -> s.durationMin
            else -> "60"
        }
        fun timed(dt: LocalDateTime) = out(fmt(dt), dur = eventDuration(true))

        // With a recurrence, a day code outside the grammar ("every day") is the rule restated: ignore it.
        val day = parseDay(dayCode, now).let {
            // A weekday or "today" next to a rule is usually the rule's own day restated
            // ("every Monday" -> weekday:MO); the rule decides the first occurrence.
            val restated = dayCode.trim().lowercase().let { d -> d.startsWith("weekday:") || d == "today" }
            if (s.recurrence.isNotBlank() && (it == Day.Ask("when") || restated)) Day.None else it
        }
        if (s.recurrence.isNotBlank() && day == Day.None) {
            if (isEvent && time == null && Regex("FREQ=YEARLY", RegexOption.IGNORE_CASE).containsMatchIn(s.recurrence)) {
                return out(expect = "clarify:when")
            }
            val first = firstOccurrence(s.recurrence, time, now) ?: return out(expect = "clarify:when")
            return timed(first)
        }
        return when (day) {
            is Day.Ask -> out(expect = "clarify:${day.what}")
            is Day.Moment -> timed(if (time != null) day.at.toLocalDate().atTime(time) else day.at)
            is Day.Shifted -> timed(if (time != null) day.at.toLocalDate().atTime(time) else day.at)
            is Day.On -> when {
                time != null -> {
                    val dt = day.date.atTime(time)
                    if (dt <= now && !isEdit) out(expect = "clarify:past-time") else timed(dt)
                }
                isEdit -> out(day.date.toString())
                isEvent -> out(day.date.toString(), dur = "")
                isAlarm -> out(expect = "clarify:time")
                else -> {
                    val dt = day.date.atTime(9, 0)
                    if (dt <= now) out(expect = "clarify:time") else timed(dt)
                }
            }
            Day.None -> when {
                time == null -> if (isEdit) out() else out(expect = if (isAlarm) "clarify:time" else "clarify:when")
                else -> {
                    val todayAt = now.toLocalDate().atTime(time)
                    when {
                        isEdit || todayAt > now -> timed(todayAt)
                        isAlarm || clock is Clock.Part -> timed(todayAt.plusDays(1))
                        else -> out(expect = "clarify:time")
                    }
                }
            }
        }
    }
}

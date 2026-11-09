package com.hapuya.tools.intenteval

/**
 * Checks the model's codes against the words the user actually said, and corrects
 * a code when the words leave only one reading (A6, 8 Nov 2026). The small on-phone
 * model often writes a code for words that are not there: "weekend" for "Saturday",
 * the prompt example's "Tuesday" for "Thursday", "vague" next to "in 20 minutes",
 * "payment" for "Block 30 minutes". The words are read with fixed patterns, so they
 * are trusted over the model whenever a pattern matches cleanly.
 *
 * Each rule only fixes or removes something; when the words are unclear the model's
 * code is kept and the resolver's own rules still ask. A payment stays blocked unless
 * the words hold no money word at all. The command is data: nothing here acts on it.
 */
object CrossCheck {
    private val DAY_WORDS = linkedMapOf(
        // "sat"/"sun"/"mon" are also plain words, so short forms count only after on/this/next/every.
        "MO" to "monday|(?<=(on|this|next|every) )mon", "TU" to "tues?(day)?", "WE" to "wed(nes)?(day)?|weds",
        "TH" to "thu(rs?)?(day)?", "FR" to "fri(day)?",
        "SA" to "saturday|(?<=(on|this|next|every) )sat", "SU" to "sunday|(?<=(on|this|next|every) )sun",
    )
    private val NUM = mapOf(
        "a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
        "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12,
        "fifteen" to 15, "twenty" to 20, "thirty" to 30, "forty" to 40, "forty-five" to 45, "sixty" to 60,
        "a couple of" to 2, "a few" to 3,
    )
    private const val NUMBER = """(\d+|a couple of|a few|an|a|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|fifteen|twenty|thirty|forty-five|forty|sixty)"""
    private const val UNIT = """(minute|min|hour|hr|day|week|month|year)s?"""

    private fun rx(p: String) = Regex(p, RegexOption.IGNORE_CASE)
    private fun has(w: String, p: String) = rx(p).containsMatchIn(w)

    private val EVERY = rx("""\b(every|each|daily|weekly|monthly|yearly|annually|weekdays|hourly|nightly)\b""")
    private const val HALF = """(\s+and\s+a\s+half)?"""
    private val IN_OFFSET = rx("""\bin\s+$NUMBER$HALF\s+$UNIT\b$HALF""")
    private val FROM_NOW = rx("""\b$NUMBER$HALF\s+$UNIT$HALF\s+from now\b""")
    private val HALF_HOUR = rx("""\bin (half an hour|a half hour)\b""")
    private val AMPM = rx("""\b(\d{1,2})(?:[:.](\d{2}))?\s*([ap])\.?m\b\.?""")
    private val AT_BARE = rx("""\b(?:at|for|around|about|by)\s+(\d{1,2})(?:[:.](\d{2}))?\b(?!\s*(?:[ap]\.?m|minutes?|mins?|hours?|hrs?|seconds?|secs?|days?|weeks?|/|th|st|nd|rd|people|tickets?))""")
    private val RANGE = rx("""\bfrom\s+(\d{1,2})(?::(\d{2}))?\s*(?:([ap])\.?m\.?)?\s*(?:to|until|till|-)\s*(\d{1,2})(?::(\d{2}))?\s*(?:([ap])\.?m\.?)?""")
    private val PART_WORDS = listOf(
        "end of (the )?day|eod|close of business" to "eod",
        // "lunch" only as a time ("at lunch", "lunchtime"), not a task ("pack lunch").
        "lunchtime|(at|by|around|over|after|before) lunch" to "lunch",
        "tonight" to "tonight",
        "morning" to "morning",
        "afternoon" to "afternoon",
        "evening" to "evening",
        "night" to "night",
    )
    private val HOLIDAYS = listOf(
        "new year['’]?s['’]? eve|nye" to "new-years-eve",
        "christmas eve|xmas eve" to "christmas-eve",
        "new year['’]?s['’]?( day)?" to "new-year",
        "christmas|xmas" to "christmas",
        "thanksgiving" to "thanksgiving",
        "halloween" to "halloween",
        "valentine['’]?s['’]?( day)?" to "valentines",
        "independence day|fourth of july|july 4(th)?" to "independence-day",
    )
    private val MONEY = rx("""[$£€₦]|\b(pay|paid|payment|send money|transfer|venmo|zelle|cash ?app|wire|dollars?|pounds?|euros?|naira|bucks)\b""")
    private val COMMERCE = rx("""\b(buy|order|purchase|book|reserve|tickets?|table for)\b""")
    private val EVENT_VERB = rx("""^\s*(add|schedule|put|block|create|set up a (meeting|call))\b""")
    private val REMIND = rx("""\bremind me\b|^\s*(set|add|create|make) a reminder\b|^\s*reminder\b|\bdon['’]?t let me forget\b|\bi need to remember\b|^\s*remember to\b""")
    /** An amount or currency: "Pay day" alone is not money, "$50" or "20 pounds" is. */
    private val STRONG_MONEY = rx("""[$£€₦]|\b\d+(\.\d+)?\s*(dollars?|pounds?|euros?|naira|bucks|quid)\b|\b(send|transfer|wire)\s+(money|cash)\b""")
    private val FULL_DAYS = mapOf("MO" to "monday", "TU" to "tuesday", "WE" to "wednesday", "TH" to "thursday", "FR" to "friday", "SA" to "saturday", "SU" to "sunday")
    private fun namedDays(w: String) = DAY_WORDS.keys.filter { has(w, """\b(${DAY_WORDS.getValue(it)})s?\b""") }
    private val PLACE = rx("""\bwhen (i|we)(['’]m| am)? (get|arrive|leave|reach|at|near|go|walk|pass|back|home)\b|\bwhen (i|we) get home\b""")
    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private const val MONTH = """(january|february|march|april|may|june|july|august|september|october|november|december|jan|feb|mar|apr|jun|jul|aug|sept?|oct|nov|dec)"""
    private val MONTH_DATE = rx("""\b$MONTH\.?\s+(\d{1,2})(st|nd|rd|th)?\b(?!\s*(:|am|pm|minutes?|hours?))""")
    private val DATE_MONTH = rx("""\b(?:the\s+)?(\d{1,2})(st|nd|rd|th)?\s+(?:of\s+)?$MONTH\b""")
    private val SLASH_DATE = rx("""\b(\d{1,2})/(\d{1,2})(?:/(\d{4}))?\b""")
    private val ORDINAL_DAY = rx("""\bthe\s+(\d{1,2})(st|nd|rd|th)\b(?!\s+of\s+(every|each|the)\s+month)""")

    fun check(s: SplitIntent, said: String): SplitIntent {
        val w = said.trim()
        if (w.isEmpty()) return s
        var intent = s.intent.trim().lowercase()
        var day = s.day.trim()
        var clock = s.clock.trim()
        var until = s.until.trim()
        var rule = s.recurrence.trim()
        var dur = s.durationMin.trim()
        var expect = s.expect.trim()

        // --- intent ---
        val remind = REMIND.containsMatchIn(w)
        val money = MONEY.containsMatchIn(w)
        val strongMoney = STRONG_MONEY.containsMatchIn(w)
        when {
            // "Remind me to pay the credit card" is a reminder about paying, not a payment.
            (intent == "payment" || intent.startsWith("booking")) && remind -> {
                intent = "reminder.create"
                if (expect.startsWith("block") || expect.startsWith("unsupported:commerce")) expect = "act"
            }
            intent == "payment" && !money && COMMERCE.containsMatchIn(w) -> { intent = "booking"; expect = "unsupported:commerce" }
            // "Put Pay day on the 30th": an event verb and no amount or currency is not a payment.
            intent == "payment" && !strongMoney && EVENT_VERB.containsMatchIn(w) -> { intent = "event.create"; expect = "act" }
            // "Add a call with Kemi" labelled a booking: no buy/book/order word, so it's a calendar entry.
            intent.startsWith("booking") && !COMMERCE.containsMatchIn(w) && EVENT_VERB.containsMatchIn(w) -> { intent = "event.create"; expect = "act" }
            intent == "event.create" && remind && !EVENT_VERB.containsMatchIn(w) -> intent = "reminder.create"
            intent == "reminder.create" && !remind && EVENT_VERB.containsMatchIn(w) -> intent = "event.create"
            !intent.startsWith("alarm") && !remind && has(w, """^\s*(wake me( up)?|set an alarm)\b""") -> intent = "alarm.set"
        }
        // An edit with the wrong or no object: the words say which kind.
        Regex("^(reminder\\.|event\\.)?(delete|move)$").matchEntire(intent)?.let { m ->
            val kind = when {
                has(w, """\breminders?\b""") -> "reminder"
                has(w, """\b(event|meeting|appointment|calendar)s?\b""") -> "event"
                else -> m.groupValues[1].removeSuffix(".")
            }
            if (kind.isNotEmpty()) intent = "$kind.${m.groupValues[2]}"
        }
        if (intent == "reminder.create" && PLACE.containsMatchIn(w)) {
            return s.copy(intent = intent, day = "", clock = "", until = "", recurrence = "", expect = "unsupported:location")
        }
        if (intent == "payment" || intent.startsWith("booking") || intent == "timer.set" || intent == "reminder.snooze") {
            if (intent == "timer.set") timerMinutes(w)?.let { dur = it }
            return s.copy(intent = intent, durationMin = dur, expect = expect)
        }

        // "Change my Friday alarm": changing an existing alarm is the clock app's job.
        if (intent == "alarm.update") return s.copy(intent = intent, expect = "unsupported:clock-app")

        // --- recurrence: only when a repeat word was said ---
        // Plural full day names ("on Mondays") also mean every week.
        val pluralDays = DAY_WORDS.keys.filter { has(w, """\b${FULL_DAYS.getValue(it)}s\b""") }
        val everyDays = DAY_WORDS.keys.filter {
            has(w, """\bevery\s+(\w+\s*(,|and)\s*)*(${DAY_WORDS.getValue(it)})s?\b""") || it in pluralDays
        }
        val everyN = rx("""\bevery\s+$NUMBER\s+(minute|min|hour|day|week|month)s?\b""").find(w)
            ?.let { m -> num(m.groupValues[1])?.let { it to m.groupValues[2].lowercase().let { u -> if (u == "min") "minute" else u } } }
            ?: rx("""\bevery\s+other\s+(day|week|month)\b""").find(w)?.let { 2 to it.groupValues[1].lowercase() }
        fun interval(n: Int) = if (n > 1) ";INTERVAL=$n" else ""
        val monthDay = rx("""\b(\d{1,2})(st|nd|rd|th)\s+of\s+(every|each|the)\s+month\b""").find(w)?.groupValues?.get(1)
        val yearDate = dateFromWords(w)?.takeIf { it.startsWith("date:") }?.removePrefix("date:")?.split('/')
        when {
            !EVERY.containsMatchIn(w) && pluralDays.isEmpty() -> rule = ""
            has(w, """\bevery\s+weekday\b|\bweekdays\b""") -> rule = "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR"
            has(w, """\bevery\s+weekend\b|\bweekends\b""") -> rule = "FREQ=WEEKLY;BYDAY=SA,SU"
            everyN?.second == "week" -> rule = "FREQ=WEEKLY" + interval(everyN.first) +
                (namedDays(w).takeIf { it.isNotEmpty() }?.let { ";BYDAY=" + it.joinToString(",") } ?: "")
            everyDays.isNotEmpty() -> rule = "FREQ=WEEKLY;BYDAY=" + everyDays.joinToString(",")
            everyN?.second == "minute" -> rule = "FREQ=MINUTELY" + interval(everyN.first)
            everyN?.second == "hour" -> rule = "FREQ=HOURLY" + interval(everyN.first)
            everyN?.second == "month" -> rule = "FREQ=MONTHLY" + interval(everyN.first)
            has(w, """\bevery\s+hour\b|\bhourly\b""") -> rule = "FREQ=HOURLY"
            everyN?.second == "day" -> rule = "FREQ=DAILY" + interval(everyN.first)
            has(w, """\b(every|each)\s+(day|morning|evening|night|afternoon)\b|\bdaily\b|\bnightly\b""") -> rule = "FREQ=DAILY"
            monthDay != null -> rule = "FREQ=MONTHLY;BYMONTHDAY=$monthDay"
            has(w, """\blast day of (every|each|the) month\b""") -> rule = "FREQ=MONTHLY;BYMONTHDAY=-1"
            has(w, """\b(every|each) year\b""") && yearDate != null -> rule = "FREQ=YEARLY;BYMONTH=${yearDate[0]};BYMONTHDAY=${yearDate[1]}"
            has(w, """\b(yearly|annually|every year|each year)\b""") && rule.isEmpty() -> rule = "FREQ=YEARLY"
        }
        // Weekly, daily and hourly rules carry their own first day; dated rules keep the day code.
        val ruleSetsDay = rule.isNotEmpty() && !rule.contains("BYMONTH") &&
            !rule.startsWith("FREQ=MONTHLY") && !rule.startsWith("FREQ=YEARLY")

        // --- day ---
        // "Set a nap alarm for 20 minutes": an alarm "for" a length is an offset.
        val alarmFor = if (intent == "alarm.set") rx("""\bfor\s+$NUMBER\s+(minute|min|hour|hr)s?\b""").find(w)?.let { m ->
            num(m.groupValues[1])?.let { "in:$it:" + if (m.groupValues[2].startsWith("h", true)) "hour" else "minute" }
        } else null
        val offset = offset(w) ?: alarmFor
        val holiday = HOLIDAYS.firstOrNull { (p, _) -> has(w, """\b($p)""") }?.second
        val named = DAY_WORDS.keys.filter { has(w, """\b(${DAY_WORDS.getValue(it)})\b""") }
        val nextDay = DAY_WORDS.keys.firstOrNull { has(w, """\bnext\s+(${DAY_WORDS.getValue(it)})\b""") }
        val weekendSaid = has(w, """\bweekend\b""")
        val code = day.lowercase()
        val date = dateFromWords(w)
        when {
            // "every year on May 9": the rule carries the date; keep the model's day code.
            date != null && !rule.contains("BYMONTH") && !rule.startsWith("FREQ=MONTHLY") -> day = date
            offset != null -> {
                day = offset
                if (clock.lowercase().startsWith("in:") || clock.lowercase() == "vague") clock = ""
            }
            holiday != null -> day = "holiday:$holiday"
            rule.isNotEmpty() && ruleSetsDay -> day = ""
            nextDay != null -> day = "next:$nextDay"
            named.size == 1 && !weekendSaid && !code.startsWith("dom:") && !code.startsWith("date:") -> day = "weekday:${named[0]}"
            named.isEmpty() && !weekendSaid && (code.startsWith("weekday:") || code.startsWith("next:") || code == "weekend") -> day = dayFromWords(w)
            code == "tomorrow" && !has(w, """\btomorrow\b""") -> day = dayFromWords(w)
            code == "today" && dayFromWords(w).isEmpty() -> day = ""
            (code.isEmpty() || code == "vague" || code == "today") && dayFromWords(w).let { it == "tomorrow" || it == "yesterday" || it == "in:2:day" } -> day = dayFromWords(w)
            code.isEmpty() && dayFromWords(w) == "today" -> day = "today"
            code == "vague" && dayFromWords(w) != "vague" -> day = dayFromWords(w)
        }
        // "tomorrow evening or the day after": two days offered, so ask.
        if ((intent == "reminder.create" || intent == "event.create") &&
            has(w, """\b(today|tonight|tomorrow|\w+day)\b.*\bor\s+(the day after|tomorrow|today|next week|\w+day)\b""")) expect = "clarify:when"

        // --- clock ---
        val part = PART_WORDS.firstOrNull { (p, _) -> has(w, """\b($p)\b""") }?.second
        val range = RANGE.find(w)
        val ampm = AMPM.find(w)
        val bare = AT_BARE.find(w)
        val shortOffset = offset?.let { it.endsWith("minute") || it.endsWith("hour") } == true
        val spoken = spokenTime(w)
        val moving = intent.endsWith(".move") || intent.endsWith(".update")
        when {
            // "Move my 2pm meeting to 4pm": the time after "to" is the new one; "Push my 3pm
            // reminder to Friday": the only time stays. Anything else keeps the model's.
            moving -> {
                val to = rx("""\bto\s+(\d{1,2})(?::(\d{2}))?\s*([ap])\.?m\b""").find(w)
                val all = AMPM.findAll(w).toList()
                val pick = to ?: all.singleOrNull()
                if (pick != null) clock = hhmm(pick.groupValues[1].toInt(), pick.groupValues[2], pick.groupValues[3])
            }
            range == null && AMPM.findAll(w).count() > 1 -> {}
            shortOffset -> clock = ""
            range != null -> {
                val g = range.groupValues
                val h1 = g[1].toInt(); val h2 = g[4].toInt(); val endAp = g[6].lowercase()
                val startAp = g[3].lowercase().ifEmpty {
                    when { endAp.isEmpty() -> ""; h1 == 12 -> "p"; h1 <= h2 -> endAp; endAp == "p" -> "a"; else -> "p" }
                }
                fun code(h: Int, m: String, ap: String) = if (ap.isEmpty()) "bare:$h" + (if (m.isNotEmpty()) ":$m" else "") else hhmm(h, m, ap)
                clock = code(h1, g[2], startAp)
                until = code(h2, g[5], endAp)
            }
            ampm != null -> clock = hhmm(ampm.groupValues[1].toInt(), ampm.groupValues[2], ampm.groupValues[3])
            spoken != null -> clock = if (part != null && part != "lunch" && part != "eod") "part:$part@$spoken" else "bare:$spoken"
            has(w, """\b(noon|midday)\b""") -> clock = "12:00"
            has(w, """\bmidnight\b""") -> clock = "00:00"
            bare != null && bare.groupValues[1].toInt() in 1..12 -> {
                val hm = bare.groupValues[1] + (bare.groupValues[2].takeIf { it.isNotEmpty() }?.let { ":$it" } ?: "")
                clock = if (part != null && part != "lunch" && part != "eod") "part:$part@$hm" else "bare:$hm"
            }
            bare != null && bare.groupValues[1].toInt() in 13..23 -> clock = "%02d:%s".format(bare.groupValues[1].toInt(), bare.groupValues[2].ifEmpty { "00" })
            // A clock word the patterns cannot read ("ten to six"): keep the model's clock.
            part != null && !has(w, """\b(half|quarter|past|o['’]?clock)\b|\b\w+ to (one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|\d)\b""") -> clock = "part:$part"
        }

        // --- duration of an event ---
        if (intent == "event.create") {
            val m = rx("""\bfor\s+$NUMBER\s+(minute|min|hour|hr)s?\b""").find(w)
                ?: rx("""^\s*block\s+(?:out\s+)?$NUMBER\s+(minute|min|hour|hr)s?\b""").find(w)
                ?: rx("""\b$NUMBER-(minute|min|hour|hr)\b""").find(w)
            m?.let { num(it.groupValues[1]) }?.let { n ->
                dur = (if (m.groupValues[2].startsWith("h", true)) n * 60 else n).toString()
                // A said length beats an end time the words did not give.
                if (range == null) until = ""
            }
        }

        // "which day?" is only right when "next <day>" was said.
        if (expect.startsWith("clarify:which-day") && nextDay == null) expect = "act"
        return SplitIntent(intent, s.title, day, clock, until, rule, dur, expect)
    }

    /**
     * Rules only, no model: guesses the intent from the words, then lets [check] fill
     * the codes. Measures what the words alone give (8 Nov 2026). Dates ("November 3",
     * "the 15th") have no pattern in [check] yet, so they come out empty and the
     * resolver asks.
     */
    fun fromWords(said: String): SplitIntent = check(SplitIntent(intentFromWords(said), "", "", "", "", "", "", "act"), said)

    fun intentFromWords(said: String): String {
        val w = said.trim()
        val reminderWord = has(w, """\breminders?\b""")
        return when {
            has(w, """^\s*(what|which|show|list|do i have)\b""") ->
                if (reminderWord) "reminder.list" else "event.list"
            has(w, """\bsnooze\b""") -> "reminder.snooze"
            has(w, """^\s*(delete|cancel|remove|clear)\b""") -> if (reminderWord) "reminder.delete" else "event.delete"
            has(w, """^\s*(change|move|update|edit)\b.*\balarm\b""") -> "alarm.update"
            has(w, """^\s*(move|reschedule|push|change)\b""") -> if (reminderWord) "reminder.move" else "event.move"
            has(w, """^\s*(rename|retitle|update)\b""") -> if (reminderWord) "reminder.update" else "event.update"
            REMIND.containsMatchIn(w) -> "reminder.create"
            has(w, """^\s*(wake me|set an alarm|set alarm|alarm)\b|\balarm (for|at)\b""") -> "alarm.set"
            has(w, """\b(timer|countdown|count down)\b""") -> "timer.set"
            // An event word with no amount or currency ("Book a dentist appointment", "Put Pay day…").
            !STRONG_MONEY.containsMatchIn(w) && (EVENT_VERB.containsMatchIn(w) || has(w, """\b(calendar|meeting|appointment)\b""")) -> "event.create"
            MONEY.containsMatchIn(w) && has(w, """\b(pay|send|transfer|wire)\b""") -> "payment"
            COMMERCE.containsMatchIn(w) -> "booking"
            EVENT_VERB.containsMatchIn(w) -> "event.create"
            else -> "other"
        }
    }

    private fun num(s: String): Int? = s.toIntOrNull() ?: NUM[s.lowercase()]

    private const val HOUR = """(\d{1,2}|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve)"""

    /** "half past 8" -> "8:30", "quarter past 3" -> "3:15", "quarter to 6" -> "5:45", British "at half six" -> "6:30". */
    private fun spokenTime(w: String): String? {
        fun h(s: String) = num(s)?.takeIf { it in 1..12 }
        rx("""\bhalf past $HOUR\b""").find(w)?.let { m -> return h(m.groupValues[1])?.let { "$it:30" } }
        rx("""\bquarter past $HOUR\b""").find(w)?.let { m -> return h(m.groupValues[1])?.let { "$it:15" } }
        rx("""\bquarter to $HOUR\b""").find(w)?.let { m -> return h(m.groupValues[1])?.let { "${if (it == 1) 12 else it - 1}:45" } }
        rx("""\b(?:at|for)\s+half $HOUR\b""").find(w)?.let { m -> return h(m.groupValues[1])?.let { "$it:30" } }
        return null
    }

    /** "in 20 minutes", "in a couple of hours", "an hour from now", "in half an hour" -> "in:N:unit". */
    private fun offset(w: String): String? {
        if (HALF_HOUR.containsMatchIn(w)) return "in:30:minute"
        // "in 2 hours and 30 minutes" -> 150 minutes.
        rx("""\bin\s+$NUMBER\s+(?:hour|hr)s?\s+and\s+(\d{1,2})\s+(?:minute|min)s?\b""").find(w)?.let { m ->
            num(m.groupValues[1])?.let { h -> return "in:${h * 60 + m.groupValues[2].toInt()}:minute" }
        }
        val m = IN_OFFSET.find(w) ?: FROM_NOW.find(w) ?: return null
        val n = num(m.groupValues[1]) ?: return null
        val unit = when (val u = m.groupValues[3].lowercase()) { "min" -> "minute"; "hr" -> "hour"; else -> u }
        val half = m.groupValues[2].isNotEmpty() || m.groupValues[4].isNotEmpty()
        // "an hour and a half" -> 90 minutes; other halves have no code, so leave the model's.
        if (half) return if (unit == "hour") "in:${n * 60 + 30}:minute" else null
        return "in:$n:$unit"
    }

    /** "November 15th", "15 November", "12/20" -> "date:M/D"; "the 2nd" -> "dom:2"; null if none. */
    private fun dateFromWords(w: String): String? {
        MONTH_DATE.find(w)?.let { m ->
            val d = m.groupValues[2].toInt()
            if (d in 1..31) return "date:${MONTHS.indexOf(m.groupValues[1].lowercase().take(3)) + 1}/$d"
        }
        DATE_MONTH.find(w)?.let { m ->
            val d = m.groupValues[1].toInt()
            if (d in 1..31) return "date:${MONTHS.indexOf(m.groupValues[3].lowercase().take(3)) + 1}/$d"
        }
        SLASH_DATE.find(w)?.let { m ->
            val (a, b) = m.groupValues[1].toInt() to m.groupValues[2].toInt()
            // US order (en-US is the only locale in v1 beta): month/day.
            if (a in 1..12 && b in 1..31) return "date:$a/$b" + (m.groupValues[3].takeIf { it.isNotEmpty() }?.let { "/$it" } ?: "")
        }
        ORDINAL_DAY.find(w)?.let { m ->
            val d = m.groupValues[1].toInt()
            if (d in 1..31) {
                // "Friday the 13th": the weekday narrows the date.
                val dow = DAY_WORDS.keys.firstOrNull { has(w, """\b(${DAY_WORDS.getValue(it)})\s+the\s+$d""") }
                return "dom:$d" + (dow?.let { ":$it" } ?: "")
            }
        }
        return null
    }

    /** The day the words give when the model's day code was not said. */
    private fun dayFromWords(w: String): String = when {
        has(w, """\bday after tomorrow\b""") -> "in:2:day"
        has(w, """\btomorrow\b""") -> "tomorrow"
        has(w, """\byesterday\b""") -> "yesterday"
        has(w, """\b(today|tonight|this (morning|afternoon|evening))\b""") -> "today"
        has(w, """\b(soon|later|sometime|some time|at some point|one day)\b""") -> "vague"
        else -> ""
    }

    private fun hhmm(h12: Int, min: String, ap: String): String {
        val pm = ap.startsWith("p", true)
        val h = when { h12 == 12 && !pm -> 0; h12 == 12 -> 12; pm && h12 < 12 -> h12 + 12; else -> h12 }
        return "%02d:%s".format(h, min.ifEmpty { "00" })
    }

    /** "3 minutes and 30 seconds" -> 3.5, "two and a half minutes" -> 2.5, "1 hour" -> 60; null if unclear. */
    private fun timerMinutes(w: String): String? {
        var total = 0.0
        var found = false
        for (m in rx("""(?<!half )\b$NUMBER$HALF[\s-]+(hour|hr|minute|min|second|sec)s?\b$HALF""").findAll(w)) {
            val n = num(m.groupValues[1]) ?: return null
            val v = n + if (m.groupValues[2].isNotEmpty() || m.groupValues[4].isNotEmpty()) 0.5 else 0.0
            total += when (m.groupValues[3].lowercase().take(1)) { "h" -> v * 60; "m" -> v; else -> v / 60 }
            found = true
        }
        rx("""\bhalf an? (hour|minute)\b""").find(w)?.let { total += if (it.groupValues[1].lowercase() == "hour") 30.0 else 0.5; found = true }
        if (!found || total <= 0) return null
        return if (total == Math.floor(total)) total.toLong().toString() else total.toString()
    }
}

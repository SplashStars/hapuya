package com.hapuya.tools.intenteval

import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Feeds the resolver the ideal split codes for real rows of the phrase set and
 * checks the result against the row's expected values. If a row fails here, the
 * bug is in the resolver, not the model.
 */
class ResolverTest {
    /** id -> day | clock | until | duration_min | expect (intent, title, recurrence come from the row). */
    private val codes = mapOf(
        1 to "tomorrow|15:00", 2 to "today|bare:4", 3 to "|11:00", 4 to "|bare:3:15", 5 to "tomorrow|07:30",
        7 to "|12:00", 8 to "|part:tonight", 9 to "|part:afternoon", 10 to "|part:evening", 11 to "tomorrow|part:morning",
        13 to "tomorrow|part:evening", 15 to "|bare:10", 16 to "tomorrow|09:00", 17 to "|part:tonight@9", 18 to "|bare:1",
        19 to "|part:eod", 20 to "|part:lunch", 30 to "|part:tonight@8:30",
        36 to "in:90:minute|", 40 to "in:5:minute|", 43 to "in:2:hour|", 44 to "in:1:month|", 50 to "in:3:hour|",
        53 to "weekday:TH|", 54 to "weekday:SA|part:morning", 55 to "weekday:SU|part:evening", 56 to "weekday:MO|08:00",
        57 to "weekday:FR|bare:4", 58 to "weekday:TU|bare:11", 60 to "weekday:TH|part:afternoon", 62 to "weekday:FR|17:30",
        70 to "weekday:SA|12:00", 75 to "weekday:WE|bare:6",
        76 to "date:11/3|", 77 to "date:11/5|14:00", 80 to "dom:30|", 81 to "dom:2|bare:10", 83 to "holiday:christmas-eve|",
        84 to "holiday:new-year|12:00", 85 to "holiday:halloween|17:00", 86 to "holiday:thanksgiving|", 88 to "date:10/29|18:00",
        92 to "date:12/31|23:00", 93 to "dom:13:FR|", 94 to "date:1/15|",
        96 to "|08:00", 97 to "|bare:9:30", 98 to "|bare:10", 100 to "|part:night@10", 101 to "|", 102 to "|", 103 to "|part:morning",
        104 to "|18:00", 105 to "|07:00", 106 to "|13:00", 107 to "|", 108 to "|10:00", 109 to "|", 110 to "|", 111 to "|",
        112 to "|bare:12:30", 113 to "|part:evening", 114 to "|bare:9",
        116 to "|", 119 to "|", 123 to "vague|", 124 to "vague|", 126 to "|part:morning", 127 to "weekend|",
        128 to "||||unsupported:location", 130 to "tomorrow|",
        131 to "tomorrow|14:00", 132 to "weekday:TH|bare:11||30", 135 to "weekday:WE|bare:3|17:00", 136 to "date:11/12|",
        137 to "|bare:10", 139 to "weekday:FR|part:morning||120", 142 to "dom:4:WE|10:00", 146 to "today|bare:4|bare:4:30",
        147 to "dom:30|", 150 to "|10:00", 153 to "date:3/3|", 155 to "date:11/17|bare:1|14:30", 158 to "today|bare:11:45||15",
        161 to "tomorrow|bare:6:30", 162 to "|bare:7", 163 to "|05:45", 164 to "|14:00", 165 to "in:10:minute|", 166 to "|06:00",
        167 to "weekday:SA|bare:8:15", 168 to "in:20:minute|", 170 to "|06:30",
        171 to "|||10", 175 to "|||11",
        181 to "|15:00", 182 to "tomorrow|", 183 to "today|", 185 to "weekday:FR|15:00", 186 to "|16:00", 187 to "|14:00",
        188 to "tomorrow|", 189 to "|||10|clarify:which", 190 to "weekday:FR|07:00|||unsupported:clock-app",
        191 to "next:FR|", 192 to "|bare:8", 193 to "|", 194 to "date:4/11|", 196 to "|17:00|||block:money", 197 to "|",
        198 to "yesterday|bare:3", 199 to "|25:00", 200 to "weekday:FR||||unsupported:commerce",
    )

    /** Same check for the holdout set, written after split mode was tuned. */
    private val holdoutCodes = mapOf(
        1 to "today|16:30", 2 to "tomorrow|10:00", 3 to "|bare:2", 4 to "|bare:5", 5 to "|12:00", 6 to "|part:tonight",
        7 to "|part:evening@7", 8 to "tomorrow|part:afternoon", 9 to "|bare:9:15", 10 to "|part:lunch", 11 to "|part:tonight@8",
        12 to "|part:eod", 13 to "in:45:minute|", 14 to "in:3:hour|", 15 to "in:2:day|", 16 to "in:1:week|", 17 to "in:15:minute|",
        18 to "in:1:hour|", 19 to "in:2:week|", 20 to "weekday:WE|", 21 to "weekday:FR|15:00", 22 to "weekday:SU|part:morning",
        23 to "weekday:MO|09:00", 24 to "weekday:TU|part:evening", 25 to "weekday:SA|bare:10", 26 to "weekday:TH|bare:4",
        27 to "date:11/8|", 28 to "date:12/1|15:00", 29 to "dom:5|", 30 to "date:11/20|", 31 to "holiday:new-years-eve|20:00",
        32 to "holiday:christmas|part:morning", 33 to "date:2/14|", 34 to "|17:00", 35 to "|21:00", 36 to "|part:evening",
        37 to "|", 38 to "|", 39 to "|part:morning", 40 to "|18:00", 41 to "|", 42 to "vague|", 43 to "||||unsupported:location",
        44 to "tomorrow|", 45 to "weekday:TH|14:00", 46 to "weekday:FR|bare:12|bare:1:30", 47 to "tomorrow|bare:10:15",
        48 to "date:11/14|", 49 to "weekday:WE|part:afternoon||60", 50 to "|09:00||20", 51 to "weekday:SA|20:00",
        52 to "tomorrow|07:45", 53 to "|bare:6", 54 to "|15:00", 55 to "in:30:minute|", 56 to "|||5", 57 to "|||20", 58 to "|||2.5",
        59 to "weekday:FR|", 60 to "|11:00", 61 to "next:TU|", 62 to "||||block:money", 63 to "|part:tonight|||unsupported:commerce",
        64 to "|bare:7",
    )

    /** Holdout v2 (7 Nov 2026), written to check accuracy work on the short instructions. */
    private val holdout2Codes = mapOf(
        1 to "|15:45", 2 to "tomorrow|08:00", 3 to "|bare:4", 4 to "|bare:11", 5 to "|part:afternoon", 6 to "|part:tonight@10",
        7 to "tomorrow|part:morning", 8 to "|bare:1:30", 9 to "|18:15", 10 to "|bare:10:30", 11 to "|part:evening", 12 to "|part:eod",
        13 to "in:20:minute|", 14 to "in:4:hour|", 15 to "in:3:day|", 16 to "in:30:minute|", 17 to "in:2:hour|", 18 to "in:10:day|",
        19 to "in:1:month|", 20 to "weekday:FR|", 21 to "weekday:TU|14:00", 22 to "weekday:SA|part:afternoon", 23 to "weekday:WE|bare:9",
        24 to "weekday:TH|part:evening", 25 to "weekday:MO|", 26 to "weekday:SU|bare:3", 27 to "date:11/12|", 28 to "date:12/24|18:00",
        29 to "dom:15|", 30 to "date:12/3|", 31 to "holiday:halloween|17:00", 32 to "holiday:thanksgiving|part:morning", 33 to "date:1/6|",
        34 to "|19:00", 35 to "|part:morning@7", 36 to "|08:30", 37 to "|", 38 to "|", 39 to "|part:evening", 40 to "|",
        41 to "|", 42 to "vague|", 43 to "||||unsupported:location", 44 to "tomorrow|", 45 to "weekday:WE|11:00",
        46 to "weekday:TH|bare:3|17:30", 47 to "weekday:FR|13:00", 48 to "date:11/19|", 49 to "tomorrow|16:00||30",
        50 to "|10:00||15", 51 to "weekday:SA|bare:11", 52 to "tomorrow|06:30", 53 to "|bare:7", 54 to "|14:00", 55 to "in:1:hour|",
        56 to "|||12", 57 to "|||45", 58 to "|||3.5", 59 to "tomorrow|", 60 to "|15:00", 61 to "next:TH|", 62 to "||||block:money",
        63 to "weekday:SA||||unsupported:commerce", 64 to "|bare:8",
    )

    /** Holdout v3 (8 Nov 2026), written after the word check, before any model saw it. */
    private val holdout3Codes = mapOf(
        1 to "|14:15", 2 to "tomorrow|12:00", 3 to "|bare:5", 4 to "|bare:9:45", 5 to "today|part:morning@11", 6 to "|part:tonight",
        7 to "tomorrow|part:afternoon", 8 to "|bare:5:45", 9 to "|19:05", 10 to "|part:afternoon", 11 to "|part:lunch",
        12 to "in:90:minute|", 13 to "in:5:minute|", 14 to "in:2:day|", 15 to "in:90:minute|", 16 to "in:6:week|", 17 to "in:2:week|",
        18 to "in:45:minute|", 19 to "weekday:WE|part:morning", 20 to "weekday:FR|16:30", 21 to "weekday:TH|12:00", 22 to "weekday:TU|",
        23 to "weekday:SU|part:night", 24 to "weekday:SA|part:morning@8", 25 to "weekday:MO|bare:2", 26 to "weekday:FR|",
        27 to "date:11/3|", 28 to "dom:28|", 29 to "date:12/1|10:00", 30 to "holiday:christmas-eve|15:00", 31 to "holiday:valentines|",
        32 to "holiday:new-year|", 33 to "|06:00", 34 to "|part:night@10", 35 to "|20:00", 36 to "|", 37 to "|part:afternoon", 38 to "|",
        39 to "|", 40 to "|part:morning", 41 to "|", 42 to "vague|", 43 to "||||unsupported:location", 44 to "||||unsupported:location",
        45 to "weekday:TH|14:00", 46 to "tomorrow|bare:11|bare:11:30", 47 to "weekday:FR|09:15", 48 to "weekday:WE|part:afternoon||120",
        49 to "date:11/10|10:00||90", 50 to "|18:00", 51 to "holiday:thanksgiving|", 52 to "|05:45", 53 to "tomorrow|bare:6:30",
        54 to "|15:00", 55 to "in:2:hour|", 56 to "|||25", 57 to "|||90", 58 to "|||1.5", 59 to "tomorrow|", 60 to "weekday:FR|",
        61 to "||||block:money", 62 to "|19:00|||unsupported:commerce", 63 to "next:MO|", 64 to "|bare:7",
    )

    @Test
    fun holdout3IdealCodesResolveToExpectedValues() = check("reminder-phrases-holdout-v3.csv", holdout3Codes)

    /** Holdout v4 (8 Nov 2026), written before rules-only was scored and before any model saw it. */
    private val holdout4Codes = mapOf(
        1 to "|16:20", 2 to "|bare:3", 3 to "tomorrow|09:30", 4 to "|part:evening@6", 5 to "|part:afternoon@2:30",
        6 to "tomorrow|part:night", 7 to "|bare:11:15", 8 to "|20:45", 9 to "|bare:5", 10 to "tomorrow|part:morning",
        11 to "weekday:SA|20:00|||unsupported:commerce", 12 to "in:15:minute|", 13 to "in:30:minute|", 14 to "in:150:minute|",
        15 to "in:4:day|", 16 to "in:3:week|", 17 to "in:2:month|", 18 to "in:10:minute|", 19 to "in:2:day|",
        20 to "weekday:TH|13:00", 21 to "weekday:SA|", 22 to "weekday:TU|bare:10", 23 to "weekday:WE|part:evening",
        24 to "weekday:FR|12:00", 25 to "weekday:SU|part:afternoon", 26 to "weekday:MO|", 27 to "weekday:MO|part:morning",
        28 to "date:10/30|", 29 to "dom:2|11:00", 30 to "date:11/15|16:00", 31 to "holiday:halloween|part:morning",
        32 to "date:12/20|", 33 to "holiday:thanksgiving|14:00", 34 to "date:1/3|", 35 to "|07:00", 36 to "|21:00", 37 to "|18:00",
        38 to "|", 39 to "|", 40 to "|07:30", 41 to "|part:evening@7", 42 to "|", 43 to "|", 44 to "vague|",
        45 to "||||unsupported:location", 46 to "||||unsupported:location", 47 to "weekday:TH|bare:12:30",
        48 to "tomorrow|15:00||45", 49 to "weekday:FR|bare:2|15:00", 50 to "tomorrow|part:morning||60", 51 to "date:12/2|09:00",
        52 to "|09:15||15", 53 to "date:11/28|", 54 to "|06:00", 55 to "tomorrow|bare:7:15", 56 to "|16:30", 57 to "in:30:minute|",
        58 to "|||8", 59 to "|||20", 60 to "|||60", 61 to "weekday:FR|", 62 to "|17:00", 63 to "||||block:money", 64 to "|bare:9",
    )

    // The check was run on holdout 4 (8 Nov 2026) after its honest score was taken.
    // Its gaps were then fixed, so a holdout 5 is needed for the next honest score.
    @Test
    fun holdout4IdealCodesResolveToExpectedValues() = check("reminder-phrases-holdout-v4.csv", holdout4Codes)

    /** Holdout v5 (9 Nov 2026), written after the phrasing work, before anything was scored on it. */
    private val holdout5Codes = mapOf(
        1 to "|17:30", 2 to "tomorrow|12:00", 3 to "|22:00", 4 to "|bare:4:30", 5 to "|part:evening@8",
        6 to "tomorrow|part:morning@7:45", 7 to "|bare:3:30", 8 to "|part:tonight@9:30", 9 to "|bare:1", 10 to "|part:morning@10:15",
        11 to "in:1:hour|", 12 to "in:45:minute|", 13 to "in:25:minute|", 14 to "in:1:year|", 15 to "in:5:day|", 16 to "in:3:week|",
        17 to "in:2:hour|", 18 to "in:1:hour|", 19 to "weekday:FR|15:00", 20 to "weekday:TH|part:morning@9:30", 21 to "weekday:WE|bare:5",
        22 to "weekday:TU|part:evening", 23 to "weekday:SA|09:00", 24 to "weekday:SU|12:00", 25 to "weekday:MO|08:30", 26 to "next:FR|",
        27 to "date:12/3|", 28 to "date:12/3|14:00", 29 to "date:11/30|09:00", 30 to "dom:31|", 31 to "holiday:christmas|12:00",
        32 to "holiday:independence-day|", 33 to "date:3/15|", 34 to "|bare:9", 35 to "|", 36 to "|part:morning@6:30",
        37 to "|part:evening", 38 to "|", 39 to "|", 40 to "|08:00", 41 to "|part:night", 42 to "|", 43 to "vague|",
        44 to "||||unsupported:location", 45 to "||||unsupported:location", 46 to "weekday:FR|10:00", 47 to "tomorrow|14:00||30",
        48 to "weekday:SA|19:00", 49 to "weekday:TH|13:00||90", 50 to "date:11/9|bare:10|11:00", 51 to "|16:00", 52 to "date:12/12|",
        53 to "tomorrow|part:morning@6:15", 54 to "|05:00", 55 to "|14:30", 56 to "in:90:minute|", 57 to "|||15", 58 to "|||2",
        59 to "|||75", 60 to "weekday:FR|", 61 to "|10:00", 62 to "|17:00", 63 to "||||block:money",
        64 to "weekday:FR||||unsupported:commerce",
    )

    // Check on since 9 Nov 2026, after holdout 5 was scored; its gaps were then fixed.
    @Test
    fun holdout5IdealCodesResolveToExpectedValues() = check("reminder-phrases-holdout-v5.csv", holdout5Codes)

    @Test
    fun holdout2IdealCodesResolveToExpectedValues() = check("reminder-phrases-holdout-v2.csv", holdout2Codes)

    @Test
    fun idealCodesResolveToExpectedValues() = check("reminder-phrases-v1.csv", codes)

    @Test
    fun holdoutIdealCodesResolveToExpectedValues() = check("reminder-phrases-holdout-v1.csv", holdoutCodes)

    private fun check(name: String, codes: Map<Int, String>, withCheck: Boolean = true) {
        val file = File("../../../05_Testing/testsets/$name")
        assumeTrue("phrase set not found", file.exists())
        val rows = PhraseCsv.load(file).associateBy { it.id }
        val failures = codes.mapNotNull { (id, c) ->
            val p = rows.getValue(id)
            val f = c.split('|') + List(5) { "" }
            val split = SplitIntent(p.expected.intent, p.expected.title, f[0], f[1], f[2], p.expected.recurrence, f[3], f[4].ifEmpty { "act" })
            // Right codes must stay right after the check against the words said.
            val runs = listOf("" to Resolver.resolve(split)) +
                if (withCheck) listOf("checked " to Resolver.resolve(CrossCheck.check(split, p.phrase))) else emptyList()
            runs.firstNotNullOfOrNull { (tag, got) ->
                if (Scorer.score(p.expected, got).core) null
                else "#$id $tag${p.phrase}: expected ${p.expected.intent} ${p.expected.whenLocal}/${p.expected.recurrence}/${p.expected.durationMin}/${p.expected.expect}, " +
                    "got ${got.intent} ${got.whenLocal}/${got.recurrence}/${got.durationMin}/${got.expect}"
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun bareHoursFollowRuleOne() {
        assertEquals("08:00", Resolver.parseClock("bare:8").time.toString())
        assertEquals("16:00", Resolver.parseClock("bare:4").time.toString())
        assertEquals("12:00", Resolver.parseClock("bare:12").time.toString())
        assertEquals(Resolver.Clock.Invalid, Resolver.parseClock("25:00"))
        assertEquals(Resolver.Clock.Invalid, Resolver.parseClock("noon-ish"))
    }

    @Test
    fun lastDayOfMonthRule() {
        assertEquals("2026-10-31T09:00", Resolver.firstOccurrence("FREQ=MONTHLY;BYMONTHDAY=-1", null, Reference.NOW).toString())
    }

    @Test
    fun compactLineParses() {
        val s = splitFromLine("reminder.create | title:back up the photos | clock:bare:8 | recurrence:FREQ=WEEKLY;BYDAY=MO")
        assertEquals(SplitIntent("reminder.create", "back up the photos", "", "bare:8", "", "FREQ=WEEKLY;BYDAY=MO", "", "act"), s)
    }

    @Test
    fun compactLineSkipsFenceAndKeepsExpectAndInDay() {
        val s = splitFromLine("```\ntimer.set | duration_min:10 | day:in:2:hour | expect:clarify:which\n```")
        assertEquals(SplitIntent("timer.set", "", "in:2:hour", "", "", "", "10", "clarify:which"), s)
    }

    @Test
    fun compactLineRejectsProse() {
        assertTrue(runCatching { splitFromLine("Sure! Here is the intent.") }.isFailure)
    }

    @Test
    fun compactPromptHasNoJsonInstruction() {
        val p = Mode.COMPACT.systemPrompt()
        assertTrue("one-line format" in p && "JSON object matching" !in p && "name:value" in p)
    }

    @Test
    fun compactLineTakesUnlabelledDayCodes() {
        val s = splitFromLine("reminder.create | title:move the car | in:3:hour")
        assertEquals("in:3:hour", s.day)
        assertEquals("tomorrow", splitFromLine("reminder.create | title:x | tomorrow | clock:part:morning").day)
    }

    @Test
    fun tidyFixesOneReadingSlips() {
        fun r(day: String, clock: String, rec: String = "", intent: String = "reminder.create") =
            Resolver.resolve(SplitIntent(intent, "t", day, clock, "", rec, "", "act"))
        assertEquals("2026-10-27T18:00", r("weekday:TU", "evening").whenLocal)
        assertEquals("2026-12-25T09:00", r("holiday:christmas-morning", "").whenLocal)
        assertEquals("2026-12-31T20:00", r("holiday:new-year's-eve", "20:00").whenLocal)
        r("in:1:week", "", "FREQ=WEEKLY;INTERVAL=1").let { assertEquals("2026-11-02T09:00", it.whenLocal); assertEquals("", it.recurrence) }
        assertEquals("FREQ=DAILY", r("", "part:evening", "FREQ=DAILY;BYHOUR=18").recurrence)
        assertEquals("unsupported:commerce", r("", "part:tonight", intent = "booking.create").expect)
    }

    /** Phone slips seen on 8 Nov 2026 (A6 holdout 2), each corrected by the words said. */
    @Test
    fun crossCheckFixesCodesThatWereNotSaid() {
        fun r(said: String, line: String) = Resolver.resolve(CrossCheck.check(Resolver.tidy(splitFromLine(line)), said))
        fun at(said: String, line: String) = r(said, line).let { listOf(it.intent, it.whenLocal, it.recurrence, it.durationMin, it.expect).filter(String::isNotEmpty).joinToString(" ") }
        assertEquals("reminder.create 2026-10-31T15:00 act", at("Remind me on Saturday afternoon to mow the lawn", "reminder.create | title:mow | day:weekend | clock:bare:afternoon"))
        assertEquals("reminder.create 2026-10-29T19:00 FREQ=WEEKLY;BYDAY=TH act", at("Remind me every Thursday at 7pm to call my sister", "reminder.create | title:call | day:weekday:TU | clock:19:00 | recurrence:FREQ=WEEKLY;BYDAY=TU"))
        assertEquals("reminder.create 2026-10-26T09:20 act", at("Remind me in 20 minutes to take the cake out", "reminder.create | title:cake | day:vague | clock:in:20:minute"))
        assertEquals("event.create 2026-10-27T16:00 30 act", at("Block 30 minutes tomorrow at 4pm for email", "payment | title:email | day:tomorrow | clock:16:00 | block:money"))
        assertEquals("reminder.create clarify:which-day", at("Remind me next Thursday to send the report", "reminder.create | title:report | day:weekday:TH"))
        assertEquals("booking unsupported:commerce", at("Buy me two tickets for the concert on Saturday", "payment | title:tickets | day:weekend | block:money"))
        assertEquals("event.create 2026-10-29T15:00 150 act", at("Schedule a workshop on Thursday from 3 to 5:30pm", "event.create | title:workshop | day:weekday:TH | clock:15:30 | until:17:30"))
        // Safety: a real payment stays blocked, whatever else the model wrote.
        assertEquals("payment block:money", at("Send \$50 to Kemi tomorrow", "payment | title:Kemi | day:tomorrow"))
        assertEquals("payment block:money", at("Pay the electricity bill", "payment | title:bill"))
        // Without the words (check off), the model's codes are used as they are.
        assertEquals("reminder.create clarify:when", at("", "reminder.create | title:x | day:weekend | clock:15:00"))
    }

    @Test
    fun shortCompactPromptAsksForOneLineOnly() {
        val p = Mode.SHORT_COMPACT.systemPrompt()
        assertTrue("weekday:TU" in p && "name:value" in p && "JSON object only" !in p && "into JSON" !in p && "{\"intent\"" !in p)
    }
}

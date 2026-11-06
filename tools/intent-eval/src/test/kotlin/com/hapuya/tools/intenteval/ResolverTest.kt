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

    @Test
    fun idealCodesResolveToExpectedValues() {
        val file = File("../../../05_Testing/testsets/reminder-phrases-v1.csv")
        assumeTrue("phrase set not found", file.exists())
        val rows = PhraseCsv.load(file).associateBy { it.id }
        val failures = codes.mapNotNull { (id, c) ->
            val p = rows.getValue(id)
            val f = c.split('|') + List(5) { "" }
            val split = SplitIntent(p.expected.intent, p.expected.title, f[0], f[1], f[2], p.expected.recurrence, f[3], f[4].ifEmpty { "act" })
            val got = Resolver.resolve(split)
            if (Scorer.score(p.expected, got).core) null
            else "#$id ${p.phrase}: expected ${p.expected.whenLocal}/${p.expected.durationMin}/${p.expected.expect}, got ${got.whenLocal}/${got.durationMin}/${got.expect}"
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
}

package com.hapuya.tools.intenteval

import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScorerTest {
    private val base = Intent("reminder.create", "Call Ada", "2026-10-27T15:00", "", "", "act")

    @Test
    fun exactMatchScoresFull() {
        assertTrue(Scorer.score(base, base).full)
    }

    @Test
    fun wrongTimeFailsCore() {
        assertFalse(Scorer.score(base, base.copy(whenLocal = "2026-10-27T03:00")).core)
    }

    @Test
    fun clarifyReasonLabelIsFreeButCategoryIsNot() {
        val expected = base.copy(whenLocal = "", expect = "clarify:when")
        assertTrue(Scorer.score(expected, expected.copy(expect = "clarify:time")).core)
        assertFalse(Scorer.score(expected, expected.copy(expect = "act")).core)
    }

    @Test
    fun rruleComparisonIgnoresOrderAndPrefix() {
        assertEquals(
            Scorer.normaliseRrule("FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR"),
            Scorer.normaliseRrule("RRULE:BYDAY=FR,TH,WE,TU,MO;FREQ=WEEKLY"),
        )
    }

    @Test
    fun durationsCompareAsNumbers() {
        assertTrue(Scorer.sameNumber("60", "60.0"))
        assertTrue(Scorer.sameNumber("", ""))
        assertFalse(Scorer.sameNumber("1.5", "15"))
    }

    @Test
    fun titleCheckIsLooseButNotBlind() {
        assertTrue(Scorer.titleMatches("Call Ada", "call Ada"))
        assertTrue(Scorer.titleMatches("Take vitamins", "Take my vitamins"))
        assertTrue(Scorer.titleMatches("", "anything"))
        assertFalse(Scorer.titleMatches("Call Ada", "Buy milk"))
    }

    @Test
    fun csvSplitHandlesQuotedCommas() {
        assertEquals(
            listOf("97", "recurring", "x", "FREQ=WEEKLY;BYDAY=MO,TU", ""),
            PhraseCsv.splitRow("97,recurring,x,\"FREQ=WEEKLY;BYDAY=MO,TU\","),
        )
    }

    @Test
    fun intentJsonParsesAndDefaultsMissingFields() {
        val i = intentFromJson("""{"intent":"timer.set","duration_min":"10","expect":"act"}""")
        assertEquals(Intent("timer.set", "", "", "", "10", "act"), i)
    }

    /** The shipped phrase set loads and the oracle scores 100%, so the scorer agrees with the data. */
    @Test
    fun oracleScoresFullOnRealPhraseSet() {
        val file = File("../../../05_Testing/testsets/reminder-phrases-v1.csv")
        assumeTrue("phrase set lives outside the repo; skipped here", file.exists())
        val phrases = PhraseCsv.load(file)
        assertEquals(200, phrases.size)
        val oracle = OracleProvider()
        assertTrue(phrases.all { Scorer.score(it.expected, oracle.parse(it).intent!!).full })
        assertTrue(phrases.all { Scorer.category(it.expected.expect) in setOf("act", "clarify", "unsupported", "block") })
    }
}

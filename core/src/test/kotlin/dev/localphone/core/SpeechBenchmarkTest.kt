package dev.localphone.core

import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*

class SpeechBenchmarkTest {
    private val context = SpeechContext(entities = listOf(SpeechEntity("home", SpeechEntityKind.PLACE, "집"),
        SpeechEntity("office", SpeechEntityKind.PLACE, "회사")))
    @Test fun semanticAccuracyDistinguishesSpacingFromEntityErrors() = runBlocking {
        val same = SpeechBenchmark.measure("집으로 가자", "집으로  가자", context, false)
        assertFalse(same.exact); assertTrue(same.semantic!!); assertTrue(same.entity!!)
        val wrong = SpeechBenchmark.measure("집으로 가자", "회사로 가자", context, false)
        assertTrue(wrong.intent!!); assertFalse(wrong.entity!!); assertFalse(wrong.semantic!!)
    }
    @Test fun confidenceBlockPreventsAToolPlanAccuracyPass() = runBlocking {
        val result = SpeechBenchmark.measure("집으로 가자", "집으로 가자", context, true)
        assertTrue(result.exact); assertFalse(result.toolPlan!!)
    }
    @Test fun unsupportedAgentToolsAreNotInventedAsRecognitionErrors() = runBlocking {
        val result = SpeechBenchmark.measure("이 노래 저장해", "이 노래 저장해", context, false)
        assertTrue(result.exact); assertTrue(result.semantic!!); assertNull(result.intent)
        assertEquals("NOT_RUN_STT_EVALUATION_ONLY", result.execution)
    }
    @Test fun ellipticalKnownPlaceIsScoredAsTheSameNavigationEntity() = runBlocking {
        val result = SpeechBenchmark.measure("집으로", "집으로 가자", context, false)
        assertFalse(result.exact); assertTrue(result.semantic!!); assertTrue(result.entity!!)
    }
    @Test fun erasingANegationCountsAsAnIntentAndToolPlanFailure() = runBlocking {
        val preserved = SpeechBenchmark.measure("집으로 가지 마", "집으로 가지 마", context, false)
        assertTrue(preserved.semantic!!); assertTrue(preserved.toolPlan!!)
        val erased = SpeechBenchmark.measure("집으로 가지 마", "집으로 가자", context, false)
        assertFalse(erased.semantic!!); assertFalse(erased.intent!!); assertFalse(erased.toolPlan!!)
        assertNull(erased.entity)
    }
}

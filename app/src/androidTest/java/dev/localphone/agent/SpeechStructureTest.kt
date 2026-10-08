package dev.localphone.agent

import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.localphone.agent.runtime.OnDeviceSpeechInput
import dev.localphone.agent.runtime.SpeechVocabulary
import androidx.test.platform.app.InstrumentationRegistry
import dev.localphone.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpeechStructureTest {
    @Test fun nativeBundlePreservesEveryReturnedHypothesisAndConfidenceRank() {
        val result = OnDeviceSpeechInput.hypotheses(Bundle().apply {
            putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("지브로 가자", "집으로 가자", "김포로 가자"))
            putFloatArray(SpeechRecognizer.CONFIDENCE_SCORES, floatArrayOf(.62f, .58f, .12f))
        })
        assertEquals(3, result.size); assertEquals("집으로 가자", result[1].text)
        assertEquals(.58f, result[1].acousticConfidence!!, .0001f); assertEquals(2, result[2].rank)
    }
    @Test fun missingInvalidAndSentinelConfidenceRemainUnknown() {
        val result = OnDeviceSpeechInput.hypotheses(Bundle().apply {
            putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("a", "b", "c", "d", "e"))
            putFloatArray(SpeechRecognizer.CONFIDENCE_SCORES, floatArrayOf(-1f, Float.NaN, 1.1f, 0f))
        })
        assertNull(result[0].acousticConfidence); assertNull(result[1].acousticConfidence)
        assertNull(result[2].acousticConfidence); assertEquals(0f, result[3].acousticConfidence!!, 0f)
        assertNull(result[4].acousticConfidence)
    }
    @Test fun nativeIntentPinsKoreanAndRequestsMultipleResultsAndBoundedVocabulary() {
        val request = OnDeviceSpeechInput.recognitionIntent((0..99).map { "단어$it" })
        assertEquals("ko-KR", request.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
        assertEquals(5, request.getIntExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 0))
        assertEquals(48, request.getStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS)!!.size)
        assertFalse(request.getBooleanExtra(RecognizerIntent.EXTRA_ENABLE_BIASING_DEVICE_CONTEXT, true))
        assertFalse(request.hasExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS))
    }
    @Test fun supportedSlotDoesNotBecomeSecondDestinationBesideRealLocalAlias() = runBlocking {
        val graph = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as AgentApplication
        val place = UserPlace("synthetic-office-uuid", "테스트 지사", listOf("회사"), Coordinates(36.1, 128.1), "합성 주소", "TEST", PlaceSource.MANUAL, 1, 1)
        try {
            graph.places.saveConfirmed(place)
            val context = SpeechVocabulary.build(graph, emptyList(), null, null)
            val resolved = ContextualTranscriptResolver().resolve(SpeechRecognitionResult(
                listOf(SpeechHypothesis("회사로 안내해 줘", .35f, 0)), engine = "FIXTURE", onDevice = true), context)
            assertFalse(resolved.requiresClarification)
            assertEquals(1, context.entities.count { it.name == "회사" || "회사" in it.aliases })
        } finally { graph.places.delete(place.id) }
    }
}

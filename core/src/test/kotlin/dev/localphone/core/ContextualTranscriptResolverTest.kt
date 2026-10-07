package dev.localphone.core

import org.junit.Test
import org.junit.Assert.*

class ContextualTranscriptResolverTest {
    private val resolver = ContextualTranscriptResolver()
    private val context = SpeechContext(entities = listOf(
        SpeechEntity("home", SpeechEntityKind.PLACE, "집", listOf("우리집")),
        SpeechEntity("office", SpeechEntityKind.PLACE, "회사"),
        SpeechEntity("factory", SpeechEntityKind.PLACE, "공장"),
        SpeechEntity("parents", SpeechEntityKind.PLACE, "본가"),
        SpeechEntity("kakao", SpeechEntityKind.APP, "카카오톡"),
    ), toolPhrases = listOf("노래 멈춰", "노래 틀어", "다음 곡"))
    private fun result(vararg pairs: Pair<String, Float?>) = SpeechRecognitionResult(pairs.mapIndexed { rank, (text, confidence) ->
        SpeechHypothesis(text, confidence, rank) }, engine = "TEST", onDevice = true)
    @Test fun usesCompatibleNBestRatherThanOnlyLeadingText() {
        val fixed = resolver.resolve(result("지브로 가자" to .62f, "집으로 가자" to .58f), context)
        assertEquals("집으로 가자", fixed.selectedText); assertFalse(fixed.requiresClarification)
        assertEquals(2, fixed.originalHypotheses.size)
        assertTrue(fixed.evidence.contains("ASR_RANK=1")); assertTrue(fixed.correctionApplied)
    }
    @Test fun generalLiaisonAlsoWorksForOtherDynamicPlaces() {
        val fixed = resolver.resolve(result("공장으로 가자" to .8f), context)
        assertEquals("공장으로 가자", fixed.selectedText)
        val dynamic = SpeechContext(entities = listOf(SpeechEntity("workshop", SpeechEntityKind.PLACE, "작업실")))
        val linked = resolver.resolve(result("자겁실로 가자" to .7f), dynamic)
        assertEquals("작업실로 가자", linked.selectedText)
    }
    @Test fun unknownEntityIsNeverChangedToAConvenientHome() {
        val fixed = resolver.resolve(result("김포로 가자" to .8f), context)
        assertEquals("김포로 가자", fixed.selectedText); assertFalse(fixed.correctionApplied)
    }
    @Test fun distinctKnownDestinationsRemainActualAmbiguity() {
        val fixed = resolver.resolve(result("회사로 가자" to .75f, "집으로 가자" to .72f), context)
        assertTrue(fixed.requiresClarification); assertEquals("회사로 가자", fixed.selectedText)
    }
    @Test fun clearLeadingEntityNeverGetsReplacedByLowerRankEntity() {
        val fixed = resolver.resolve(result("회사로 가자" to .95f, "집으로 가자" to .60f), context)
        assertEquals("회사로 가자", fixed.selectedText); assertFalse(fixed.requiresClarification)
    }
    @Test fun homophonousDifferentPlacesRequireSelection() {
        val dynamic = SpeechContext(entities = listOf(SpeechEntity("a", SpeechEntityKind.PLACE, "작업실"),
            SpeechEntity("b", SpeechEntityKind.PLACE, "자겁실")))
        val fixed = resolver.resolve(result("작업실로 가자" to .8f, "자겁실로 가자" to .8f), dynamic)
        assertTrue(fixed.requiresClarification)
    }
    @Test fun sharedPlaceAliasOffersRealNamesSoOneChoiceCanResolveTheGoal() {
        val dynamic = SpeechContext(entities = listOf(SpeechEntity("a", SpeechEntityKind.PLACE, "수원역점", listOf("단골")),
            SpeechEntity("b", SpeechEntityKind.PLACE, "시청점", listOf("단골"))))
        val fixed = resolver.resolve(result("단골 가자" to .8f), dynamic)
        assertTrue(fixed.requiresClarification); assertEquals("단골 가자", fixed.selectedText)
        assertEquals(setOf("수원역점으로 가자", "시청점으로 가자"), fixed.candidates.map { it.text }.toSet())
        assertTrue(fixed.candidates.all { it.text != "단골 가자" })
    }
    @Test fun particlesAndSpacingCanBeComparedWithoutWordSubstitutionRules() {
        assertEquals(1f, KoreanPhonetics.similarity("집으로", "지브로"), .0001f)
        assertEquals(1f, KoreanPhonetics.similarity("작업실로", "자겁실로"), .0001f)
        assertEquals(1f, KoreanPhonetics.similarity("우리 집 으로", "우리집으로"), .0001f)
        assertTrue(KoreanPhonetics.similarity("회사로", "집으로") < .86f)
    }
    @Test fun bareKnownDirectionalPhraseGetsLinguisticEllipsisExpansion() {
        val fixed = resolver.resolve(result("지브로" to .7f), context)
        assertEquals("집으로 가자", fixed.selectedText)
        assertTrue(fixed.evidence.contains("DIRECTIONAL_ELLIPSIS_EXPANDED"))
    }
    @Test fun lowAcousticConfidenceCannotAuthorizeGeneratedDestination() {
        val fixed = resolver.resolve(result("지브로 가자" to .2f), context)
        assertEquals("지브로 가자", fixed.selectedText); assertTrue(fixed.requiresClarification)
    }
    @Test fun missingConfidenceIsPreservedAndOnlyExactPhoneticsCanGenerate() {
        val fixed = resolver.resolve(result("지브로 가자" to null), context)
        assertNull(fixed.confidence); assertEquals("집으로 가자", fixed.selectedText)
        val altered = resolver.resolve(result("지포로 가자" to null), context)
        assertEquals("지포로 가자", altered.selectedText)
    }
    @Test fun cancellationCannotBeErasedByMoreConvenientHypothesis() {
        val fixed = resolver.resolve(result("집으로 가지 마" to .7f, "집으로 가자" to .9f), context)
        assertEquals("집으로 가지 마", fixed.selectedText); assertFalse(fixed.correctionApplied)
    }
    @Test fun partialsNeverProduceExecutableTranscript() {
        val fixed = resolver.resolve(result("집으로 가자" to .99f).copy(finalResult = false), context)
        assertEquals("", fixed.selectedText); assertTrue(fixed.requiresClarification)
    }
    @Test fun appsUseInstalledVocabularyAndGeneralConsonantDistance() {
        val fixed = resolver.resolve(result("카카오독 켜줘" to .8f), context)
        assertEquals("카카오톡 켜줘", fixed.selectedText)
    }
    @Test fun differentMediaActionsAreNotBlindlyChosenFromNBest() {
        val fixed = resolver.resolve(result("노래 멈춰" to .70f, "노래 틀어" to .68f), context)
        assertTrue(fixed.requiresClarification)
    }
    @Test fun numericalEntitiesAreNeverSynthesizedFromVocabulary() {
        val fixed = resolver.resolve(result("3번 공장으로 가자" to .7f), context)
        assertEquals("3번 공장으로 가자", fixed.selectedText)
    }
    @Test fun unmeasuredConfidenceForMessageSendingRequiresClarification() {
        val fixed = resolver.resolve(result("엄마에게 문자 보내줘" to null), context)
        assertTrue(fixed.requiresClarification)
    }
    @Test fun conflictingMessageRecipientsCannotPassEvenWithHighConfidence() {
        val fixed = resolver.resolve(result("엄마에게 문자 보내줘" to .92f, "아빠에게 문자 보내줘" to .90f), context)
        assertTrue(fixed.requiresClarification)
    }
    @Test fun dynamicBiasIsBoundedAndDoesNotInventContacts() {
        val dynamic = context.copy(entities = context.entities + (0..100).map {
            SpeechEntity("app$it", SpeechEntityKind.APP, "앱 $it") }, biasLimit = 12)
        assertEquals(12, dynamic.biasStrings().size)
        assertTrue(dynamic.entities.none { it.kind == SpeechEntityKind.CONTACT })
        assertTrue(context.copy(biasLimit = 0).biasStrings().isEmpty())
    }
    @Test fun twoPublicDestinationsAlsoRequireClarificationWithoutLocalRegistration() {
        val fixed = resolver.resolve(result("김포로 가자" to .75f, "금포로 가자" to .73f), SpeechContext())
        assertTrue(fixed.requiresClarification)
    }
    @Test fun synonymousEndingsForOneEntityAreNotAnAmbiguity() {
        val fixed = resolver.resolve(result("집으로 가자" to .75f, "집으로 가줘" to .73f), context)
        assertFalse(fixed.requiresClarification)
    }
    @Test fun competingNegationInNBestCannotBeDiscardedBeforeExecution() {
        val fixed = resolver.resolve(result("집으로 가자" to .75f, "집으로 가지 마" to .73f), context)
        assertTrue(fixed.requiresClarification)
    }
}

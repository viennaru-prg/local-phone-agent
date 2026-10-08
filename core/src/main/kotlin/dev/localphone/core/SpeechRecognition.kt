package dev.localphone.core

data class SpeechHypothesis(val text: String, val acousticConfidence: Float?, val rank: Int)
data class SpeechPartial(val hypotheses: List<SpeechHypothesis>, val elapsedMs: Long)
data class SpeechRecognitionResult(
    val hypotheses: List<SpeechHypothesis>, val partialResults: List<SpeechPartial> = emptyList(),
    val engine: String, val onDevice: Boolean, val locale: String = "ko-KR", val finalResult: Boolean = true,
    val readyLatencyMs: Long? = null, val audioDurationMs: Long? = null, val finalLatencyMs: Long? = null,
    val totalLatencyMs: Long? = null, val biasCount: Int = 0, val source: String = "MICROPHONE",
)
enum class SpeechEntityKind { PLACE, APP, CONTACT }
data class SpeechEntity(val id: String, val kind: SpeechEntityKind, val name: String, val aliases: List<String> = emptyList(), val source: String = "CONTEXT")
data class SpeechContext(
    val entities: List<SpeechEntity> = emptyList(), val toolPhrases: List<String> = emptyList(),
    val foregroundApp: String? = null, val playbackState: String? = null, val environment: String = "GENERAL",
    val biasLimit: Int = 24,
) {
    init { require(biasLimit in 0..48) }
    fun biasStrings(): List<String> = (entities.map { it.name }.take(16) + toolPhrases.take(8) +
        entities.flatMap { it.aliases.take(2) } + entities.map { it.name }.drop(16) + toolPhrases.drop(8))
        .map(String::trim).filter { it.length in 1..80 && it.none(Char::isISOControl) }
        .distinctBy(PlaceText::normalize).take(biasLimit)
}
data class TranscriptCandidate(val text: String, val semanticKey: String, val score: Float,
    val acousticConfidence: Float?, val sourceRank: Int, val evidence: List<String>, val generated: Boolean = false,
    val selectionAliases: List<String> = emptyList())
data class ResolvedTranscript(
    val originalHypotheses: List<SpeechHypothesis>, val selectedText: String, val correctionApplied: Boolean,
    val confidence: Float?, val evidence: List<String>, val candidates: List<TranscriptCandidate>,
    val requiresClarification: Boolean = false, val clarification: String = "",
)

/** A spoken answer may restate the same entity/action with a different request ending. */
object TranscriptSelection {
    fun select(choices: List<TranscriptCandidate>, answer: ResolvedTranscript): TranscriptCandidate? {
        if (answer.requiresClarification) return null
        val text = PlaceText.normalize(answer.selectedText)
        val keys = answer.candidates.filter { PlaceText.normalize(it.text) == text }.map { it.semanticKey }.toSet()
        return choices.filter { candidate -> candidate.semanticKey in keys ||
            PlaceText.normalize(candidate.text) == text || candidate.selectionAliases.any { alias ->
                PlaceText.variants(answer.selectedText).any { it in PlaceText.variants(alias) }
            }
        }.distinctBy { it.semanticKey }.singleOrNull()
    }
}

/** Conservative local rescoring. Scores are deterministic evidence weights, not ASR probabilities. */
class ContextualTranscriptResolver {
    private data class Frame(val kind: SpeechEntityKind, val entity: String, val suffix: String, val elliptical: Boolean = false)
    private val navigation = NavigationLanguage.frame
    private val app = Regex("^(.+?)(\\s*(?:앱(?:을)?\\s*)?(?:켜줘|켜 줘|열어줘|열어 줘|실행해줘|실행해 줘))$")
    private val highRisk = Regex("전화(?:를|을)?\\s*(?:걸|해|연결)|통화(?:해|하)|메시지.*(?:보내|전송)|문자.*(?:보내|전송)|삭제|결제|송금|보내줘|전송해")
    private fun clean(text: String) = text.trim().trimEnd('.', '。', '!')
    private fun frame(text: String): Frame? {
        navigation.matchEntire(text)?.let { return Frame(SpeechEntityKind.PLACE, it.groupValues[1].trim(), it.groupValues[2]) }
        app.matchEntire(text)?.let { return Frame(SpeechEntityKind.APP, it.groupValues[1].trim().removeSuffix("을").removeSuffix("를"), it.groupValues[2]) }
        if (Regex("^\\S+(?:으로|로)$").matches(text)) return Frame(SpeechEntityKind.PLACE, text, " 가자", true)
        return null
    }
    private fun directional(name: String): String {
        val last = name.lastOrNull() ?: return name
        val coda = if (last in '가'..'힣') (last.code - 0xac00) % 28 else 0
        return name + if (coda != 0 && coda != 8) "으로" else "로"
    }
    private fun surfaces(entity: SpeechEntity, f: Frame) = (listOf(entity.name) + entity.aliases)
        .flatMap { if (f.kind == SpeechEntityKind.PLACE) listOf(it, directional(it), it + "에") else listOf(it) }
        .distinctBy(PlaceText::normalize)
    private fun matches(entity: SpeechEntity, f: Frame) = entity.kind == f.kind && surfaces(entity, f).any {
        PlaceText.normalize(it) == PlaceText.normalize(f.entity)
    }
    private fun key(text: String, f: Frame?, entity: SpeechEntity?): String = when {
        f != null -> f.kind.name + ":" + (entity?.id ?: PlaceText.variants(f.entity).minBy { it.length }) + ":" +
            if (f.kind == SpeechEntityKind.APP) "OPEN_APP" else if (NavigationLanguage.simpleEnding(f.suffix)) "NAVIGATE" else PlaceText.normalize(f.suffix)
        else -> "UTTERANCE:" + PlaceText.normalize(text)
    }
    private fun corroboratedNativeZero(result: SpeechRecognitionResult, context: SpeechContext,
        hypotheses: List<SpeechHypothesis>, best: TranscriptCandidate): Boolean {
        // Zero remains a measured low score. A converging partial stream can support a
        // literal, reversible goal without promoting that score or executing a partial.
        if (result.engine != "android-native-on-device" || !result.onDevice || result.source != "MICROPHONE" ||
            best.acousticConfidence != 0f || best.generated) return false
        fun literalKey(text: String): String? {
            if (CommandSafety.blockedReason(text) != null || highRisk.containsMatchIn(text)) return null
            val f = frame(clean(text)) ?: return null
            if (f.kind != SpeechEntityKind.PLACE || !NavigationLanguage.simpleEnding(f.suffix)) return null
            val entities = context.entities.filter { matches(it, f) }.distinctBy { it.id }
            if (entities.size > 1) return null
            return key(text, f, entities.singleOrNull())
        }
        if (hypotheses.any { literalKey(it.text) != best.semanticKey }) return false
        val finalText = PlaceText.normalize(clean(hypotheses.first().text))
        val partials = result.partialResults.flatMap { it.hypotheses }.map { clean(it.text) }.filter(String::isNotBlank)
        val observations = partials.map(PlaceText::normalize).distinct()
        // Repeated callbacks, including several at one timestamp, are not independent votes.
        if (finalText !in observations || observations.none { it != finalText && finalText.startsWith(it) }) return false
        return partials.all { text -> CommandSafety.blockedReason(text) == null && !highRisk.containsMatchIn(text) &&
            (finalText.startsWith(PlaceText.normalize(text)) || literalKey(text) == best.semanticKey) }
    }
    fun resolve(result: SpeechRecognitionResult, context: SpeechContext): ResolvedTranscript {
        val hypotheses = result.hypotheses.filter { it.text.isNotBlank() && it.text.length <= 1000 }.sortedBy { it.rank }
        if (hypotheses.isEmpty() || !result.finalResult) return ResolvedTranscript(hypotheses, "", false, null,
            listOf(if (result.finalResult) "NO_FINAL_SPEECH" else "PARTIAL_NEVER_EXECUTES"), emptyList(), true, "다시 말해 주세요.")
        val original = clean(hypotheses.first().text)
        if (CommandSafety.blockedReason(original) != null) return ResolvedTranscript(hypotheses, original, false,
            hypotheses.first().acousticConfidence, listOf("ORIGINAL_NEGATION_OR_CONDITION_PRESERVED"), emptyList())
        val originalFrame = frame(original)
        val originalEntities = originalFrame?.let { f -> context.entities.filter { matches(it, f) } }.orEmpty()
        val risky = highRisk.containsMatchIn(original)
        val all = mutableListOf<TranscriptCandidate>()
        for (hypothesis in hypotheses) {
            val text = clean(hypothesis.text)
            // A later hypothesis must not erase a negation, condition, or cancellation.
            if (CommandSafety.blockedReason(text) != null && text != original) {
                all += TranscriptCandidate(text, "BLOCKED:" + PlaceText.normalize(text),
                    .62f * (hypothesis.acousticConfidence ?: .72f) - .03f * hypothesis.rank,
                    hypothesis.acousticConfidence, hypothesis.rank, listOf("NEGATION_OR_CONDITION_HYPOTHESIS"))
                continue
            }
            val f = frame(text)
            val matched = f?.let { context.entities.filter { entity -> matches(entity, it) } }.orEmpty()
            val phoneticAgreement = KoreanPhonetics.similarity(original, text)
            val knownTool = context.toolPhrases.any { PlaceText.normalize(it) == PlaceText.normalize(text) }
            val knownCompetition = (originalEntities.isNotEmpty() && matched.isNotEmpty()) ||
                (knownTool && context.toolPhrases.any { PlaceText.normalize(it) == PlaceText.normalize(original) })
            val closeFrameCompetition = f != null && originalFrame != null && f.kind == originalFrame.kind &&
                hypothesis.acousticConfidence != null && hypotheses.first().acousticConfidence != null &&
                kotlin.math.abs(hypothesis.acousticConfidence!! - hypotheses.first().acousticConfidence!!) <= .12f
            val plausible = hypothesis.rank == hypotheses.first().rank || ((phoneticAgreement >= .86f || knownCompetition || closeFrameCompetition) &&
                (hypotheses.first().acousticConfidence == null || hypothesis.acousticConfidence == null ||
                    hypotheses.first().acousticConfidence!! - hypothesis.acousticConfidence!! <= .18f))
            if (!plausible) continue
            val base = .62f * (hypothesis.acousticConfidence ?: .72f) - .03f * hypothesis.rank
            if (matched.isEmpty()) all += TranscriptCandidate(text, key(text, f, null), base + if (f != null) .08f else if (knownTool) .25f else 0f,
                hypothesis.acousticConfidence, hypothesis.rank, listOf("ASR_RANK=${hypothesis.rank}") + if (knownTool) listOf("REGISTERED_TOOL_PHRASE") else emptyList())
            for (entity in matched) {
                val multipleIdentities = matched.map { it.id }.distinct().size > 1
                // A shared nickname must not remain the executable choice after the user selects a real name.
                val selected = if (multipleIdentities) (if (f!!.kind == SpeechEntityKind.PLACE) directional(entity.name) else entity.name) + f.suffix
                    else if (f!!.elliptical) text + f.suffix else text
                all += TranscriptCandidate(selected, key(text, f, entity), base + .40f + .09f,
                    hypothesis.acousticConfidence, hypothesis.rank, listOf("ASR_RANK=${hypothesis.rank}", "KNOWN_${entity.kind}=${entity.name}", "VOCAB_SOURCE=${entity.source}") +
                        if (f.elliptical) listOf("DIRECTIONAL_ELLIPSIS_EXPANDED") else emptyList(),
                    selectionAliases = listOf(entity.name) + entity.aliases)
            }
            if (hypothesis.rank != hypotheses.first().rank || f == null || matched.isNotEmpty() || risky ||
                hypothesis.acousticConfidence?.let { it < .50f } == true || f.entity.any(Char::isDigit)) continue
            for (entity in context.entities.filter { it.kind == f.kind }.take(64)) {
                val best = surfaces(entity, f).map { it to KoreanPhonetics.similarity(f.entity, it) }.maxByOrNull { it.second } ?: continue
                if (best.second < .90f || KoreanPhonetics.units(f.entity).size < 3 ||
                    (hypothesis.acousticConfidence == null && best.second < .999f)) continue
                val selected = best.first + f.suffix
                all += TranscriptCandidate(selected, key(selected, f, entity), base + .25f + .09f + .05f * best.second - .10f,
                    hypothesis.acousticConfidence, hypothesis.rank,
                    listOf("KNOWN_${entity.kind}=${entity.name}", "VOCAB_SOURCE=${entity.source}", "KOREAN_PHONETIC=${best.second}", "${f.kind}_PHRASE_CONTEXT") +
                        if (f.elliptical) listOf("DIRECTIONAL_ELLIPSIS_EXPANDED") else emptyList(), true,
                    selectionAliases = listOf(entity.name) + entity.aliases)
            }
        }
        val ranked = all.sortedByDescending { it.score }.distinctBy { it.semanticKey }
        var best = ranked.firstOrNull() ?: return ResolvedTranscript(hypotheses, original, false,
            hypotheses.first().acousticConfidence, listOf("NO_SUPPORTED_RESCORING_EVIDENCE"), emptyList())
        // Exact entity identity in the leading hypothesis may never be replaced with another known entity.
        if (originalEntities.isNotEmpty()) {
            val allowed = originalEntities.map { key(original, originalFrame, it) }.toSet()
            best = ranked.firstOrNull { it.semanticKey in allowed } ?: best
        }
        val collision = ranked.filter { it.semanticKey != best.semanticKey &&
            it.evidence.any { reason -> reason.startsWith("KNOWN_") || reason == "REGISTERED_TOOL_PHRASE" } &&
            best.evidence.any { reason -> reason.startsWith("KNOWN_") || reason == "REGISTERED_TOOL_PHRASE" } &&
            (best.score - it.score <= .11f || (best.acousticConfidence != null && it.acousticConfidence != null &&
                kotlin.math.abs(best.acousticConfidence!! - it.acousticConfidence!!) <= .12f)) }
        // A literal, reversible navigation request needs the same threshold as app opening.
        // This never relaxes destination substitution, competing entities, or sensitive actions.
        val literalNavigation = !best.generated && originalFrame?.kind == SpeechEntityKind.PLACE &&
            NavigationLanguage.simpleEnding(originalFrame.suffix)
        val minimum = when { risky -> .88f; originalFrame?.kind == SpeechEntityKind.APP || literalNavigation -> .25f; else -> .45f }
        val acousticLow = (risky && best.acousticConfidence == null) || best.acousticConfidence?.let { it < minimum } == true
        val riskyCompetition = risky && hypotheses.drop(1).any { other ->
            PlaceText.normalize(other.text) != PlaceText.normalize(original) &&
                (hypotheses.first().acousticConfidence == null || other.acousticConfidence == null ||
                    kotlin.math.abs(hypotheses.first().acousticConfidence!! - other.acousticConfidence!!) <= .12f)
        }
        fun identity(f: Frame): String {
            val exact = context.entities.filter { matches(it, f) }.map { it.id }.distinct()
            if (exact.size == 1) return exact.single()
            val phonetic = context.entities.filter { it.kind == f.kind && surfaces(it, f).any { surface -> KoreanPhonetics.similarity(f.entity, surface) >= .90f } }
                .map { it.id }.distinct()
            return phonetic.singleOrNull() ?: PlaceText.variants(f.entity).minBy { it.length }
        }
        val uncertainAlternatives = hypotheses.drop(1).filter { other ->
            val first = hypotheses.first().acousticConfidence; val confidence = other.acousticConfidence
            (first == null && confidence == null) || (first != null && confidence != null && kotlin.math.abs(first - confidence) <= .12f)
        }
        val entityCompetition = originalFrame != null && uncertainAlternatives.any { other ->
            frame(clean(other.text))?.let { it.kind == originalFrame.kind && identity(it) != identity(originalFrame) } == true
        }
        val safetyCompetition = uncertainAlternatives.any { CommandSafety.blockedReason(it.text) != null }
        val ambiguous = originalEntities.map { it.id }.distinct().size > 1 || collision.isNotEmpty() || riskyCompetition || entityCompetition || safetyCompetition
        val corroboratedZero = !ambiguous && !risky && literalNavigation && corroboratedNativeZero(result, context, hypotheses, best)
        val low = acousticLow && !corroboratedZero
        val alternatives = (listOf(best) + collision).distinctBy { it.semanticKey }.take(3)
        val prompt = if (ambiguous && alternatives.size > 1) alternatives.joinToString(" / ") { it.text } + " 중 어느 명령인가요?"
            else if (ambiguous) hypotheses.take(3).joinToString(" / ") { it.text } + " 중 어느 명령인가요?"
            else if (low) "잘 구분하지 못했어요. '${best.text}' 명령을 다시 말해 주세요." else ""
        return ResolvedTranscript(hypotheses, if (ambiguous || low) original else best.text,
            !ambiguous && !low && clean(best.text) != original, best.acousticConfidence,
            best.evidence + (if (literalNavigation) listOf("LITERAL_REVERSIBLE_NAVIGATION") else emptyList()) +
                (if (corroboratedZero) listOf("NATIVE_ZERO_WITH_STABLE_LITERAL", "PARTIAL_FINAL_AGREEMENT") else emptyList()) +
                if (ambiguous) listOf("COMPETING_ENTITY_OR_ACTION") else if (low) listOf("LOW_ACOUSTIC_CONFIDENCE") else emptyList(),
            ranked.take(8), ambiguous || low, prompt)
    }
}

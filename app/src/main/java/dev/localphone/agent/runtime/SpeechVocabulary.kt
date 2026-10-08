package dev.localphone.agent.runtime

import dev.localphone.agent.AgentApplication
import dev.localphone.core.*

/** Reads only existing local places, launchable apps and registered tool metadata; no contacts permission. */
object SpeechVocabulary {
    private val phrases = mapOf(
        "navigate" to listOf("길안내", "가자"),
        "open_app" to listOf("앱 켜줘", "앱 열어줘"),
        "media_resume" to listOf("노래 틀어", "음악 재생"),
        "media_pause" to listOf("노래 멈춰", "음악 일시 정지"),
        "media_next" to listOf("다음 곡"),
        "set_alarm" to listOf("알람", "오전", "오후"),
        "set_timer" to listOf("타이머"),
        "open_settings" to listOf("와이파이 설정", "블루투스 설정"),
    )
    suspend fun build(graph: AgentApplication, apps: List<AppCandidate>, foreground: String?, playback: String?): SpeechContext {
        val local = graph.places.all().sortedByDescending { it.updatedAt }.take(8).map {
            SpeechEntity(it.id, SpeechEntityKind.PLACE, it.canonicalName, it.aliases.take(8), "USER_PLACE")
        }
        val slots = PlaceSlots.aliases.map { (id, names) -> SpeechEntity(id, SpeechEntityKind.PLACE, names.first(), names.drop(1), "SUPPORTED_PLACE_SLOT") }
            .filter { slot -> local.none { place ->
                (listOf(place.name) + place.aliases).any { alias ->
                    (listOf(slot.name) + slot.aliases).any { supported -> PlaceText.variants(alias).any { it in PlaceText.variants(supported) } }
                }
            } }
        val places = (local + slots).groupBy { it.id }.map { (_, entries) -> entries.first().copy(
            aliases = entries.flatMap { listOf(it.name) + it.aliases }.distinct().filter { it != entries.first().name }) }.take(8)
        val music = graph.settings.get("music_package")
        val recent = graph.settings.get("stt_recent_app_ids").split('|')
        val preferred = listOfNotNull(foreground, music.takeIf(String::isNotBlank)) + recent +
            listOf(NaverLinks.PACKAGE, "com.android.deskclock", "com.google.android.deskclock", "com.android.settings")
        val relevantApps = apps.sortedWith(compareBy<AppCandidate> { preferred.indexOf(it.id).takeIf { index -> index >= 0 } ?: 1000 }
            .thenBy { it.name }).take(8).map { SpeechEntity(it.id, SpeechEntityKind.APP, it.name, it.aliases.take(4), "INSTALLED_APP") }
        val registered = PhoneTools.registry.tools.map { phrases[it.name].orEmpty() }
        val tools = (registered.mapNotNull { it.firstOrNull() } + registered.flatMap { it.drop(1) }).distinct()
        val limit = graph.settings.get("stt_bias_limit").toIntOrNull()?.coerceIn(0, 48) ?: 24
        return SpeechContext(places + relevantApps, tools, foreground, playback,
            graph.settings.get("stt_environment").ifBlank { "GENERAL" }, limit)
    }
}

data class SpeechDiagnostic(val recognition: SpeechRecognitionResult, val resolution: ResolvedTranscript,
    val context: SpeechContext, val resolverMs: Long)

/** One private volatile observation; persist only through the opt-in invocation diagnostics. */
class SpeechDiagnostics {
    @Volatile var last: SpeechDiagnostic? = null
        private set
    fun resolve(result: SpeechRecognitionResult, context: SpeechContext): ResolvedTranscript {
        val start = android.os.SystemClock.elapsedRealtime()
        val resolution = ContextualTranscriptResolver().resolve(result, context)
        last = SpeechDiagnostic(result, resolution, context, android.os.SystemClock.elapsedRealtime() - start)
        return resolution
    }
}

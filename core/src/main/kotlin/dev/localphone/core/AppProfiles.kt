package dev.localphone.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * What differs in one app's UI. Everything here is optional: an app without a profile is handled by
 * the generic skills, which try the common ways and remember what worked ([AppProfiles.learn]).
 */
data class AppProfile(
    val packageName: String,
    val names: List<String> = emptyList(),
    /** "music", "navigation": the app used when a command names none. */
    val roles: List<String> = emptyList(),
    /** How a list row is played: "tap" or "double_tap" (ClipStream: the first tap only selects). */
    val rowPlay: String? = null,
    /** How a list row is deleted: "button" (a delete control in the row) or "trailing_icon" (drawn in the row, no node). */
    val rowDelete: String? = null,
    /** The control that opens home/work/frequent places ("길찾기"). */
    val routeEntry: String? = null,
    /** Controls shown only while guidance runs (NAVER's driving screen has no "안내 중" text). */
    val activeGuidance: String? = null,
    /** The control that ends guidance. */
    val endGuidance: String? = null,
) {
    fun activeGuidanceRegex() = Regex(activeGuidance ?: DEFAULT_ACTIVE, RegexOption.IGNORE_CASE)
    fun endGuidanceRegex() = Regex(endGuidance ?: DEFAULT_END, RegexOption.IGNORE_CASE)

    companion object {
        const val DEFAULT_ACTIVE = "경로\\s*다시\\s*계산|reroute|(?:길|경로)?안내\\s*종료|안내\\s*중|end\\s*navigation|exit\\s*navigation"
        const val DEFAULT_END = "^(?:(?:길|경로)?안내\\s*종료|안내\\s*끝내기|end\\s*navigation|exit\\s*navigation|exit)$"
    }
}

object AppProfiles {
    private val builtIn: List<AppProfile> by lazy {
        AppProfiles::class.java.getResourceAsStream("/app-profiles.json")?.reader(Charsets.UTF_8)?.use { parse(it.readText()) }.orEmpty()
    }
    private val learned = mutableMapOf<String, MutableMap<String, String>>()

    /** Called with every learned fact so the app can keep them across runs. */
    @Volatile var persist: (Map<String, Map<String, String>>) -> Unit = {}

    fun restore(saved: Map<String, Map<String, String>>) = synchronized(learned) {
        learned.clear(); saved.forEach { (pkg, facts) -> learned[pkg] = facts.toMutableMap() }
    }

    /** Remember how this app did something ("rowPlay" → "double_tap"), for the next command. */
    fun learn(packageName: String, key: String, value: String) {
        val snapshot = synchronized(learned) {
            val facts = learned.getOrPut(packageName) { mutableMapOf() }
            if (facts[key] == value) return
            facts[key] = value
            learned.mapValues { it.value.toMap() }
        }
        persist(snapshot)
    }

    fun forPackage(packageName: String): AppProfile {
        val base = builtIn.firstOrNull { it.packageName == packageName } ?: AppProfile(packageName)
        val facts = synchronized(learned) { learned[packageName]?.toMap() }.orEmpty()
        return base.copy(rowPlay = facts["rowPlay"] ?: base.rowPlay, rowDelete = facts["rowDelete"] ?: base.rowDelete)
    }

    fun forRole(role: String): AppProfile? = builtIn.firstOrNull { role in it.roles }

    /** The spoken/launcher name of the app that plays music or navigates when a command names no app. */
    fun appFor(role: String): String? = forRole(role)?.names?.firstOrNull()

    fun parse(json: String): List<AppProfile> = runCatching {
        JsonParser.parseString(json).asJsonObject.getAsJsonArray("apps").map { it.asJsonObject.toProfile() }
    }.getOrDefault(emptyList())

    private fun JsonObject.toProfile(): AppProfile {
        fun str(key: String) = get(key)?.takeIf { it.isJsonPrimitive }?.asString
        fun list(key: String) = getAsJsonArray(key)?.map { it.asString }.orEmpty()
        return AppProfile(str("package").orEmpty(), list("names"), list("roles"), str("rowPlay"), str("rowDelete"),
            str("routeEntry"), str("activeGuidance"), str("endGuidance"))
    }
}

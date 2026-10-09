package dev.localphone.agent.access

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Build
import android.os.Process
import android.os.UserHandle

data class AppEntry(val label: String, val packageName: String, val component: ComponentName, val user: UserHandle, val space: String) {
    val display get() = if (space.isEmpty()) label else "$label ($space)"
}

/**
 * Launchable apps of every profile this app can see through LauncherApps. On Samsung devices the
 * Secure Folder appears as a separate profile when the system exposes it to launchers.
 */
class AppIndex(private val context: Context) {
    private val launcher = context.getSystemService(LauncherApps::class.java)

    fun all(): List<AppEntry> {
        val me = Process.myUserHandle()
        val profiles = runCatching { launcher.profiles }.getOrDefault(listOf(me))
        android.util.Log.i("AgentApps", "launcher profiles visible: ${profiles.size} ($profiles)")
        return profiles.flatMap { user ->
            val space = if (user == me) "" else if (Build.MANUFACTURER.equals("samsung", true)) "보안 폴더" else "다른 공간"
            runCatching { launcher.getActivityList(null, user) }.getOrDefault(emptyList()).map {
                AppEntry(it.label.toString(), it.applicationInfo.packageName, it.componentName, user, space)
            }
        }.distinctBy { it.component to it.user }
    }

    sealed interface Match {
        data class Found(val app: AppEntry) : Match
        data class Missing(val similar: List<AppEntry>) : Match
    }

    fun find(query: String, allowFuzzy: Boolean = true): Match {
        val apps = all()
        // "ClipStream (보안 폴더)" asks for the Secure Folder copy; "보안 폴더" alone is the Secure Folder app.
        val suffix = Regex("\\((.*?)\\)").find(query)?.groupValues?.get(1).orEmpty()
        val wantsOtherSpace = Regex("보안\\s*폴더|secure", RegexOption.IGNORE_CASE).containsMatchIn(suffix)
        val raw = norm(query.replace(Regex("\\(.*?\\)"), "").replace(Regex("\\s*앱$"), ""))
        if (raw.isEmpty()) return Match.Missing(emptyList())
        // People say app names in Korean while many labels are English ("유튜브" → "YouTube").
        val name = ALIASES[raw] ?: raw
        fun pick(list: List<AppEntry>): AppEntry? = list.sortedBy { (it.space.isEmpty() == wantsOtherSpace) }.firstOrNull()
        apps.filter { norm(it.label) == name }.let { pick(it) }?.let { return Match.Found(it) }
        apps.filter { norm(it.label) == raw }.let { pick(it) }?.let { return Match.Found(it) }
        // Package names carry the English name too (com.google.android.youtube).
        if (name.all { it.code < 128 } && name.length >= 4)
            apps.filter { it.packageName.lowercase().split('.').any { part -> part == name } }.let { pick(it) }?.let { return Match.Found(it) }
        if (!allowFuzzy) return Match.Missing(emptyList())
        apps.filter { norm(it.label).contains(name) || name.contains(norm(it.label)) && norm(it.label).length >= 2 }
            .let { pick(it) }?.let { return Match.Found(it) }
        val scored = apps.map { it to similarity(name, norm(it.label)) }.sortedByDescending { it.second }
        scored.firstOrNull()?.takeIf { it.second >= 0.6 }?.let { return Match.Found(it.first) }
        return Match.Missing(scored.take(5).map { it.first })
    }

    /**
     * Opens the app on its first screen. Leftovers from earlier use (an open panel, old search results,
     * a list scrolled to the end) were the most common reason a task went wrong, so the old task is cleared.
     */
    fun launch(app: AppEntry): Boolean = runCatching {
        if (app.user == Process.myUserHandle()) {
            val intent = context.packageManager.getLaunchIntentForPackage(app.packageName)
                ?: Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(app.component)
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        } else {
            launcher.startMainActivity(app.component, app.user, null, null)
        }
        true
    }.getOrDefault(false)

    private fun norm(s: String) = s.lowercase().replace(Regex("[\\s·._-]+"), "")

    companion object {
        /** Spoken Korean name → normalized launcher label. Only names whose label differs. */
        private val ALIASES = mapOf(
            "지도" to "네이버지도", "네비" to "네이버지도", "내비" to "네이버지도",
            "네비게이션" to "네이버지도", "내비게이션" to "네이버지도",
            "유튜브" to "youtube", "유투브" to "youtube", "유튜브뮤직" to "youtubemusic", "유튜브음악" to "youtubemusic",
            "크롬" to "chrome", "구글" to "google", "지메일" to "gmail", "구글맵" to "지도", "구글지도" to "지도",
            "카톡" to "카카오톡", "넷플릭스" to "netflix", "인스타" to "instagram", "인스타그램" to "instagram",
            "페이스북" to "facebook", "왓츠앱" to "whatsapp", "텔레그램" to "telegram", "디스코드" to "discord",
            "스포티파이" to "spotify", "플레이스토어" to "play스토어", "클립스트림" to "clipstream", "줌" to "zoom",
            "티맵" to "tmap", "챗지피티" to "chatgpt", "엑스" to "x", "트위터" to "x",
        )
    }
    /** Dice coefficient over character bigrams; tolerant of small STT/spacing differences. */
    private fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val x = a.windowed(2).ifEmpty { listOf(a) }; val y = b.windowed(2).ifEmpty { listOf(b) }
        val common = x.count { it in y }
        return 2.0 * common / (x.size + y.size)
    }
}

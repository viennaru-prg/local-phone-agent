package dev.localphone.agent.access

import android.content.Context
import android.content.Intent
import android.location.Geocoder
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import dev.localphone.core.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/** Direct operations: NAVER Map navigation deep link and media keys. */
class AndroidTools(private val context: Context, private val phone: AndroidPhone,
                   private val navigation: NavigationSession? = null,
                   private val listener: AgentListener = object : AgentListener {}) : Tools {

    override suspend fun prepareNavigation(): Boolean = phone.openApp("네이버 지도").ok

    override suspend fun openDirect(goal: String): String? {
        val screen = DirectGoals.settingsScreen(goal)
        val index = AppIndex(context)
        val app = if (screen == null) DirectGoals.appName(goal)?.let { index.find(it, allowFuzzy = false) as? AppIndex.Match.Found }?.app else null
        val expectedPackage: String
        if (screen != null) {
            val action = when (screen) {
                DirectGoals.SettingsScreen.WIFI -> Settings.ACTION_WIFI_SETTINGS
                DirectGoals.SettingsScreen.BLUETOOTH -> Settings.ACTION_BLUETOOTH_SETTINGS
                DirectGoals.SettingsScreen.DISPLAY -> Settings.ACTION_DISPLAY_SETTINGS
                DirectGoals.SettingsScreen.SOUND -> Settings.ACTION_SOUND_SETTINGS
                DirectGoals.SettingsScreen.BATTERY -> Intent.ACTION_POWER_USAGE_SUMMARY
            }
            val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            @Suppress("DEPRECATION")
            val target = context.packageManager.resolveActivity(intent, 0) ?: return null
            expectedPackage = target.activityInfo.packageName
            if (runCatching { context.startActivity(intent) }.isFailure) return null
        } else if (app != null) {
            expectedPackage = app.packageName
            if (!index.launch(app)) return null
        } else return DirectGoals.appName(goal)?.let { openInSecureFolder(goal, it, index) }
        val confirmed = withTimeoutOrNull(4500) { // Device Care (battery) can take a few seconds to draw
            while (true) {
                val view = phone.observe()?.let(ScreenCompactor::compact)
                if (view?.snapshot?.packageName == expectedPackage &&
                    (screen == null || DirectGoals.screenConfirmed(screen, view))) return@withTimeoutOrNull true
                delay(100)
            }
            @Suppress("UNREACHABLE_CODE") false
        } == true
        if (!confirmed) return null
        return GoalText.spokenResult(goal, "${app?.label ?: "요청한 화면"}을 열었어요.")
    }

    /**
     * Apps inside Samsung Secure Folder are invisible to this profile's launcher APIs: open Secure
     * Folder and tap the icon whose name matches ("클립스트림" → "Clipstream Player"). Null when
     * Secure Folder is locked or has no such app, so the screen agent takes over (and asks to unlock).
     */
    private suspend fun openInSecureFolder(goal: String, name: String, index: AppIndex): String? {
        val folder = (index.find("보안 폴더", allowFuzzy = false) as? AppIndex.Match.Found)?.app ?: return null
        fun matches(label: String) = GoalText.normalize(label).let { it.isNotEmpty() && it.contains(GoalText.normalize(name)) } ||
            GoalText.soundsLike(name, label)
        if (!index.launch(folder)) return null
        val (view, icon) = withTimeoutOrNull(4000) {
            while (true) {
                val view = phone.observe()?.let(ScreenCompactor::compact)
                if (view != null && view.snapshot.packageName == folder.packageName)
                    view.elements.firstOrNull { it.enabled && matches(it.label) }?.let { return@withTimeoutOrNull view to it }
                delay(150)
            }
            @Suppress("UNREACHABLE_CODE") null
        } ?: return null
        if (!phone.perform(view, AgentAction.Click(icon.id))) return null
        val opened = withTimeoutOrNull(4500) {
            while (true) {
                val now = phone.observe()
                if (now != null && now.packageName != folder.packageName && matches(now.appLabel)) return@withTimeoutOrNull true
                delay(150)
            }
            @Suppress("UNREACHABLE_CODE") false
        } == true
        Log.i(TAG, "secure folder open '$name' via '${icon.label}' opened=$opened")
        return if (opened) GoalText.spokenResult(goal, "$name 열었어요.") else null
    }

    override suspend fun navigate(place: Place): String? {
        if (place.lat == 0.0 && place.lng == 0.0) return null
        val session = navigation ?: NavigationSession.forGoal("${place.name}로 안내해줘", place)!!
        session.beforeDispatch(phone.observe()?.let { ScreenCompactor.compact(it, session.goal) })
        // NAVER Map URL scheme: starts car guidance from the current location to the coordinates.
        val uri = Uri.parse("nmap://navigation?dlat=${place.lat}&dlng=${place.lng}" +
            "&dname=${Uri.encode(place.name)}&appname=${context.packageName}")
        val intent = Intent(Intent.ACTION_VIEW, uri).setPackage(NAVER_MAP).addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (runCatching { context.startActivity(intent) }.isFailure) { Log.w(TAG, "naver deep link failed"); return null }
        val started = withTimeoutOrNull(6500) { NavigationDriver(phone, session, listener).confirm() } == true
        Log.i(TAG, "navigation guidanceObserved=$started state=${session.evidence.state}")
        if (!started) return null // The caller continues with the same navigation session in the UI agent.
        return "${place.name}${if (endsWithBatchim(place.name)) "으로" else "로"} 안내를 시작했어요."
    }

    /**
     * A media key is only a request. Success is reported only when the system audio state confirms it
     * (music started/stopped); otherwise return null so the screen agent opens the player and does it
     * on screen — e.g. ClipStream inside Secure Folder may not receive keys from this profile.
     */
    override suspend fun media(key: MediaKey): String? {
        val audio = context.getSystemService(android.media.AudioManager::class.java)
        val playingBefore = audio.isMusicActive
        if (key == MediaKey.PAUSE && !playingBefore) return "지금 재생 중인 음악이 없어요."
        if (key != MediaKey.PLAY && key != MediaKey.PAUSE && !playingBefore) return null // nothing to skip; let the agent open the player
        if (!phone.media(key)) return null
        val wanted = key != MediaKey.PAUSE
        val confirmed = withTimeoutOrNull(3000) {
            while (audio.isMusicActive != wanted) delay(150)
            true
        } ?: false
        Log.i(TAG, "media $key playingBefore=$playingBefore confirmed=$confirmed")
        if (!confirmed) return null
        return when (key) {
            MediaKey.PLAY -> "음악을 재생했어요."; MediaKey.PAUSE -> "음악을 멈췄어요."
            MediaKey.NEXT -> "다음 곡으로 넘겼어요."; MediaKey.PREVIOUS -> "이전 곡으로 갔어요."
        }
    }

    private fun endsWithBatchim(word: String): Boolean {
        val c = word.lastOrNull() ?: return false
        if (c !in '가'..'힣') return false
        val jong = (c - '가') % 28
        return jong != 0 && jong != 8 // ㄹ takes "로"
    }

    companion object {
        const val NAVER_MAP = "com.nhn.android.nmap"
        private const val TAG = "AgentTools"

        /** Address → coordinates, once per place (uses the system geocoder, which needs the network). */
        suspend fun geocode(context: Context, address: String): Pair<Double, Double>? {
            val geocoder = Geocoder(context, Locale.KOREA)
            val result = if (Build.VERSION.SDK_INT >= 33) suspendCancellableCoroutine { c ->
                geocoder.getFromLocationName(address, 1, object : Geocoder.GeocodeListener {
                    override fun onGeocode(list: MutableList<android.location.Address>) { if (c.isActive) c.resume(list.firstOrNull()) }
                    override fun onError(message: String?) { if (c.isActive) c.resume(null) }
                })
            } else @Suppress("DEPRECATION") geocoder.getFromLocationName(address, 1)?.firstOrNull()
            return result?.let { it.latitude to it.longitude }
        }
    }
}

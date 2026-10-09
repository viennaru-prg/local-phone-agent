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
        } else return null
        val confirmed = withTimeoutOrNull(2200) {
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

    override suspend fun media(key: MediaKey): String? =
        if (phone.media(key)) when (key) {
            MediaKey.PLAY -> "음악을 재생할게요."; MediaKey.PAUSE -> "음악을 멈췄어요."
            MediaKey.NEXT -> "다음 곡으로 넘길게요."; MediaKey.PREVIOUS -> "이전 곡으로 갈게요."
        } else null

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

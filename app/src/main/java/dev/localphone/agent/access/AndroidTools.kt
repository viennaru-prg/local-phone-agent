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
                DirectGoals.SettingsScreen.ALARM -> android.provider.AlarmClock.ACTION_SHOW_ALARMS
            }
            val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // No resolveActivity: package visibility hides the clock app from it, and the screen is
            // confirmed by its own title/controls below anyway.
            expectedPackage = ""
            if (runCatching { context.startActivity(intent) }.isFailure) return null
        } else if (app != null) {
            expectedPackage = app.packageName
            if (!index.launch(app)) return null
        } else return DirectGoals.appName(goal)?.let { name ->
            // Not in this profile's launcher (e.g. inside Secure Folder): openApp finds it there.
            phone.openApp(name).takeIf { it.ok }?.let { GoalText.spokenResult(goal, "$name 열었어요.") }
        }
        val confirmed = withTimeoutOrNull(4500) { // Device Care (battery) can take a few seconds to draw
            while (true) {
                val view = phone.observe()?.let(ScreenCompactor::compact)
                // Settings may hand the intent on to another app (POWER_USAGE_SUMMARY → Device Care):
                // for a settings screen its own title is the proof, whichever package shows it.
                if (view != null && (if (screen != null) DirectGoals.screenConfirmed(screen, view)
                        else view.snapshot.packageName == expectedPackage)) return@withTimeoutOrNull true
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
        // Secure Folder players report their audio state a few seconds late.
        val confirmed = withTimeoutOrNull(5000) {
            while (audio.isMusicActive != wanted) delay(150)
            true
        } ?: false
        Log.i(TAG, "media $key playingBefore=$playingBefore confirmed=$confirmed")
        if (!confirmed && key == MediaKey.PAUSE && pauseByAudioFocus(audio)) return "음악을 멈췄어요."
        if (!confirmed) return null
        return when (key) {
            MediaKey.PLAY -> "음악을 재생했어요."; MediaKey.PAUSE -> "음악을 멈췄어요."
            MediaKey.NEXT -> "다음 곡으로 넘겼어요."; MediaKey.PREVIOUS -> "이전 곡으로 갔어요."
        }
    }

    /**
     * A player the key does not reach (Secure Folder, possibly locked): take audio focus, which players
     * answer by pausing, then hand it back. No app has to be opened or unlocked.
     */
    private suspend fun pauseByAudioFocus(audio: android.media.AudioManager): Boolean {
        val request = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener { }.build()
        if (audio.requestAudioFocus(request) != android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return false
        val paused = withTimeoutOrNull(4000) { while (audio.isMusicActive) delay(150); true } ?: false
        delay(400)
        audio.abandonAudioFocusRequest(request)
        Log.i(TAG, "pause by audio focus: $paused")
        return paused
    }

    override suspend fun quick(request: QuickRequest): String? = runCatching {
        when (request) {
            QuickRequest.Time -> {
                val t = java.time.LocalTime.now()
                val h = t.hour % 12
                "지금 ${if (t.hour < 12) "오전" else "오후"} ${if (h == 0) 12 else h}시 ${t.minute}분이에요."
            }
            QuickRequest.Date -> {
                val d = java.time.LocalDate.now()
                val day = d.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Locale.KOREAN)
                "오늘은 ${d.monthValue}월 ${d.dayOfMonth}일 ${day}이에요."
            }
            QuickRequest.Battery -> {
                val bm = context.getSystemService(android.os.BatteryManager::class.java)
                val level = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
                "배터리 ${level}% 남았어요.${if (bm.isCharging) " 충전 중이에요." else ""}"
            }
            is QuickRequest.Volume -> {
                val audio = context.getSystemService(android.media.AudioManager::class.java)
                val stream = android.media.AudioManager.STREAM_MUSIC
                val max = audio.getStreamMaxVolume(stream)
                when {
                    request.toMax -> audio.setStreamVolume(stream, max, android.media.AudioManager.FLAG_SHOW_UI)
                    request.mute -> audio.adjustStreamVolume(stream, android.media.AudioManager.ADJUST_MUTE, android.media.AudioManager.FLAG_SHOW_UI)
                    else -> repeat(kotlin.math.abs(request.steps)) {
                        audio.adjustStreamVolume(stream, if (request.steps > 0) android.media.AudioManager.ADJUST_RAISE
                            else android.media.AudioManager.ADJUST_LOWER, android.media.AudioManager.FLAG_SHOW_UI)
                    }
                }
                val now = audio.getStreamVolume(stream)
                when {
                    request.mute -> "소리를 껐어요."
                    request.toMax -> "볼륨을 최대로 올렸어요."
                    request.steps > 0 -> "볼륨을 올렸어요. ($now/$max)"
                    else -> "볼륨을 내렸어요. ($now/$max)"
                }
            }
            is QuickRequest.Timer -> {
                context.startActivity(Intent(android.provider.AlarmClock.ACTION_SET_TIMER)
                    .putExtra(android.provider.AlarmClock.EXTRA_LENGTH, request.seconds)
                    .putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                val h = request.seconds / 3600; val m = request.seconds % 3600 / 60; val s = request.seconds % 60
                listOfNotNull(h.takeIf { it > 0 }?.let { "${it}시간" }, m.takeIf { it > 0 }?.let { "${it}분" }, s.takeIf { it > 0 }?.let { "${it}초" })
                    .joinToString(" ") + " 타이머를 시작했어요."
            }
            is QuickRequest.Alarm -> {
                context.startActivity(Intent(android.provider.AlarmClock.ACTION_SET_ALARM)
                    .putExtra(android.provider.AlarmClock.EXTRA_HOUR, request.hour)
                    .putExtra(android.provider.AlarmClock.EXTRA_MINUTES, request.minute)
                    .putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                val h = request.hour % 12
                "${if (request.hour < 12) "오전" else "오후"} ${if (h == 0) 12 else h}시${if (request.minute > 0) " ${request.minute}분" else ""} 알람을 맞췄어요."
            }
        }
    }.onFailure { Log.w(TAG, "quick $request failed", it) }.getOrNull()

    /**
     * "종료해줘" / "네이버 지도 종료해줘": leave the app for the home screen. (Android 14+ lets an app end
     * only its own processes, so the closed app's process is left to the system.) The map while guiding is reported instead (the drive ends first), and
     * this assistant never closes itself.
     */
    override suspend fun closeApp(name: String?, guidanceChecked: Boolean): CloseResult {
        val front = phone.observe()?.let(ScreenCompactor::compact)
        val frontPkg = front?.snapshot?.takeIf { !it.home }?.packageName
        val found = name?.let { (AppIndex(context).find(it, allowFuzzy = false) as? AppIndex.Match.Found)?.app }
        // Secure Folder apps are not in this profile's launcher: the app in front answering to the name.
        val frontNamed = name != null && front != null && GoalText.normalize(front.snapshot.appLabel).let { label ->
            label.isNotEmpty() && (label.contains(GoalText.normalize(name)) || GoalText.soundsLike(name, front.snapshot.appLabel))
        }
        val pkg = found?.packageName ?: frontPkg?.takeIf { name == null || frontNamed }
            ?: return if (name == null) CloseResult.Closed("닫을 앱이 화면에 없어요.") else CloseResult.NotAnApp
        if (pkg == context.packageName) return CloseResult.Closed("음성 비서는 닫지 않아요.")
        if (pkg == Harness.navigationApp().packageName && !guidanceChecked) return CloseResult.NavigationApp
        val label = found?.label ?: front?.snapshot?.appLabel?.takeIf { frontPkg == pkg } ?: name ?: "앱"
        val c = label.last()
        val batchim = c in '가'..'힣' && (c - '가') % 28 != 0
        if (frontPkg != pkg) return CloseResult.Closed("$label${if (batchim) "은" else "는"} 지금 화면에 없어요.")
        phone.home()
        withTimeoutOrNull(2500) { while (phone.observe()?.packageName == pkg) delay(100) }
        Log.i(TAG, "close $pkg")
        return CloseResult.Closed("$label${if (batchim) "을" else "를"} 닫았어요.")
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

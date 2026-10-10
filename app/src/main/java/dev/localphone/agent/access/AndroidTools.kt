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
                setAlarm(request.hour, request.minute, request.days, request.label)
                val days = AlarmCommands.dayWords(request.days)?.let { if (it == "매일" || it == "평일" || it == "주말") "$it " else "매주 $it " }.orEmpty()
                "$days${AlarmCommands.clock(request.hour, request.minute)}${request.label?.let { " '$it'" }.orEmpty()} 알람을 맞췄어요."
            }
            is QuickRequest.AlarmIn -> {
                val at = java.util.Calendar.getInstance().apply { add(java.util.Calendar.MINUTE, request.minutes) }
                val hour = at.get(java.util.Calendar.HOUR_OF_DAY); val minute = at.get(java.util.Calendar.MINUTE)
                setAlarm(hour, minute, emptyList(), null)
                val wait = listOfNotNull((request.minutes / 60).takeIf { it > 0 }?.let { "${it}시간" }, (request.minutes % 60).takeIf { it > 0 }?.let { "${it}분" }).joinToString(" ")
                "$wait 뒤인 ${AlarmCommands.clock(hour, minute)}에 알람을 맞췄어요."
            }
        }
    }.onFailure { Log.w(TAG, "quick $request failed", it) }.getOrNull()

    private fun setAlarm(hour: Int, minute: Int, days: List<Int>, label: String?) {
        val intent = Intent(android.provider.AlarmClock.ACTION_SET_ALARM)
            .putExtra(android.provider.AlarmClock.EXTRA_HOUR, hour)
            .putExtra(android.provider.AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (days.isNotEmpty()) intent.putExtra(android.provider.AlarmClock.EXTRA_DAYS, ArrayList(days))
        label?.let { intent.putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, it) }
        context.startActivity(intent)
    }

    /**
     * The next ring comes from the system; the list, switching and the sound are done in the clock app's
     * alarm list, read from its switches ("오전 06:30, 공휴일에는 끄기, 월요일, …").
     */
    override suspend fun alarm(request: AlarmRequest): String? {
        val nextAt = context.getSystemService(android.app.AlarmManager::class.java).nextAlarmClock?.triggerTime
        if (request == AlarmRequest.Next) return AlarmCommands.next(System.currentTimeMillis(), nextAt)
        var view = openAlarmList() ?: return null
        val rows = linkedMapOf<String, AlarmRow>()
        fun key(r: AlarmRow) = "${r.label}|${r.hour}:${r.minute}|${r.days}"
        // The list reopens where it was left: back to its top first, so no alarm above is missed.
        for (i in 1..6) {
            val list = view.lists.maxByOrNull { it.bounds.height } ?: break
            val before = AlarmCommands.rows(view).map(::key)
            phone.perform(view, AgentAction.Scroll(ScrollDir.UP, list.id)); delay(400)
            view = phone.observe()?.let(ScreenCompactor::compact) ?: return null
            if (AlarmCommands.rows(view).map(::key) == before) break
        }
        // Reads the list down to its end (it scrolls), stopping early once [found] is on screen.
        suspend fun walk(found: (AlarmRow) -> Boolean = { false }): AlarmRow? {
            repeat(6) {
                AlarmCommands.rows(view).forEach { rows[key(it)] = it }
                AlarmCommands.rows(view).firstOrNull(found)?.let { return it }
                val list = view.lists.maxByOrNull { it.bounds.height } ?: return null
                val before = AlarmCommands.rows(view).map(::key)
                phone.perform(view, AgentAction.Scroll(ScrollDir.DOWN, list.id)); delay(500)
                view = phone.observe()?.let(ScreenCompactor::compact) ?: return null
                if (AlarmCommands.rows(view).map(::key) == before) return null
            }
            return null
        }
        if (request == AlarmRequest.List) { walk(); return AlarmCommands.summary(rows.values.toList()) }
        val target = when (request) {
            is AlarmRequest.Switch -> request.target; is AlarmRequest.Sound -> request.target; is AlarmRequest.Delete -> request.target
            else -> return null
        }
        val wanted: (AlarmRow) -> Boolean = when (target) {
            AlarmTarget.Next -> { row -> nextAt != null && AlarmCommands.rowAt(listOf(row), nextAt) != null }
            is AlarmTarget.At -> { row -> AlarmCommands.rowsAt(listOf(row), target.hour, target.minute).isNotEmpty() }
        }
        val row = walk(wanted) ?: return when (target) {
            AlarmTarget.Next -> if (nextAt == null) "예정된 알람이 없어요." else null
            is AlarmTarget.At -> "${AlarmCommands.clock(target.hour, target.minute)} 알람을 찾지 못했어요."
        }
        val name = AlarmCommands.describe(row)
        return when (request) {
            is AlarmRequest.Switch -> switchAlarm(view, row, request, name, nextAt)
            is AlarmRequest.Sound -> alarmSound(view, row, request.name, name)
            is AlarmRequest.Delete -> {
                // Two alarms at that time ("평일 7시" and "토요일 7시"): ask, never pick one to delete.
                if (target is AlarmTarget.At) {
                    walk(); val same = AlarmCommands.rowsAt(rows.values.toList(), target.hour, target.minute)
                    if (same.size > 1) return "${AlarmCommands.clock(target.hour, target.minute)} 알람이 ${same.size}개 있어요: " +
                        same.joinToString(", ") { AlarmCommands.describe(it) } + ". 요일까지 말씀해 주시면 지울게요."
                    view = openAlarmList() ?: return null
                    val again = walk(wanted) ?: return null
                    return deleteAlarm(view, again, AlarmCommands.describe(again))
                }
                deleteAlarm(view, row, name)
            }
            else -> null
        }
    }

    /**
     * Long-press selects the alarm; 삭제 is pressed only when that alarm, and nothing else, is selected.
     * Done when its row is gone from the list.
     */
    private suspend fun deleteAlarm(list: ScreenView, row: AlarmRow, name: String): String? {
        suspend fun screen() = phone.observe()?.let(ScreenCompactor::compact)
        val switch = list.element(row.id) ?: return null
        val item = list.elements.firstOrNull { it.kind == Kind.ITEM && it.bounds.centerY in switch.bounds.top..switch.bounds.bottom } ?: return null
        if (!phone.perform(list, AgentAction.LongClick(item.id))) return null
        val selecting = withTimeoutOrNull(2500) {
            while (true) { delay(250); screen()?.takeIf(AlarmCommands::selecting)?.let { return@withTimeoutOrNull it } }
            @Suppress("UNREACHABLE_CODE") null
        } ?: return null
        // In selection mode the switches are the checkboxes: exactly one ticked, on this alarm's row.
        val ticked = selecting.elements.filter { it.kind == Kind.SWITCH && it.checked == true && Regex("(오전|오후)\\s*\\d{1,2}:\\d{2}").containsMatchIn(it.label) }
        val mine = ticked.singleOrNull()?.takeIf { t -> t.label.trim() == switch.label.trim() }
        Log.i(TAG, "alarm delete: want='${switch.label}' ticked=${ticked.map { it.label }} selecting=${AlarmCommands.selecting(selecting)}")
        if (mine == null) { phone.perform(selecting, AgentAction.Back); return null }
        val delete = selecting.elements.firstOrNull { it.enabled && it.kind == Kind.BUTTON && it.label.trim() == "삭제" }
            ?: run { phone.perform(selecting, AgentAction.Back); return null }
        if (!phone.perform(selecting, AgentAction.Click(delete.id))) return null
        delay(800)
        // A confirmation sheet, if the app asks.
        screen()?.let { now -> now.elements.firstOrNull { it.enabled && it.kind == Kind.BUTTON && it.label.trim() == "삭제" && AlarmCommands.selecting(now).not() }
            ?.let { phone.perform(now, AgentAction.Click(it.id)); delay(800) } }
        val gone = withTimeoutOrNull(3000) {
            while (true) {
                val now = screen()
                if (now != null && !AlarmCommands.selecting(now) && AlarmCommands.rows(now).isNotEmpty() &&
                    AlarmCommands.rows(now).none { it.label == row.label && it.hour == row.hour && it.minute == row.minute && it.days == row.days })
                    return@withTimeoutOrNull true
                delay(200)
            }
            @Suppress("UNREACHABLE_CODE") false
        } == true
        if (gone) AlarmSkips.forget(context, row.hour, row.minute, row.days, row.label)
        return if (gone) "$name 알람을 지웠어요." else null
    }

    private suspend fun openAlarmList(): ScreenView? {
        runCatching { context.startActivity(Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { return null }
        return withTimeoutOrNull(4000) {
            while (true) {
                val now = phone.observe()?.let(ScreenCompactor::compact)
                // Left in selection mode (a long press earlier): leave it before reading or changing anything.
                if (now != null && AlarmCommands.selecting(now)) { phone.perform(now, AgentAction.Back); delay(500); continue }
                now?.takeIf { AlarmCommands.rows(it).isNotEmpty() }?.let { return@withTimeoutOrNull it }
                delay(150)
            }
            @Suppress("UNREACHABLE_CODE") null
        }
    }

    private suspend fun switchAlarm(view: ScreenView, row: AlarmRow, request: AlarmRequest.Switch, name: String, nextAt: Long?): String? {
        if (row.on == request.on) return "$name 알람은 이미 ${if (row.on) "켜져" else "꺼져"} 있어요."
        if (!phone.perform(view, AgentAction.Click(row.id))) return null
        // The switch's own state is the proof.
        val switched = withTimeoutOrNull(3000) {
            while (true) {
                delay(200)
                val now = phone.observe()?.let(ScreenCompactor::compact) ?: continue
                AlarmCommands.rows(now).firstOrNull { it.label == row.label && it.hour == row.hour && it.minute == row.minute && it.days == row.days }
                    ?.takeIf { it.on == request.on }?.let { return@withTimeoutOrNull true }
            }
            @Suppress("UNREACHABLE_CODE") false
        } == true
        if (!switched) return null
        if (request.on) return "$name 알람을 켰어요."
        if (!request.once || row.days.isEmpty()) return "$name 알람을 껐어요."
        // A repeating alarm skipped once: back on right after the ring it skips.
        val ring = nextAt?.takeIf { AlarmCommands.rowAt(listOf(row), it) != null } ?: nextRing(row)
        AlarmSkips.schedule(context, AlarmSkips.Skip(row.hour, row.minute, row.days, row.label, ring))
        val day = AlarmCommands.next(System.currentTimeMillis(), ring).removePrefix("다음 알람은 ").substringBefore("이에요.")
        return "$day 알람 한 번만 울리지 않게 껐어요. 그 뒤에는 다시 켜져요."
    }

    /** When a repeating alarm rings next, from its days and time. */
    private fun nextRing(row: AlarmRow): Long {
        val c = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, row.hour); set(java.util.Calendar.MINUTE, row.minute)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }
        repeat(8) {
            if (c.timeInMillis > System.currentTimeMillis() && c.get(java.util.Calendar.DAY_OF_WEEK) in row.days) return c.timeInMillis
            c.add(java.util.Calendar.DAY_OF_MONTH, 1)
        }
        return c.timeInMillis
    }

    /**
     * Opens the alarm's sound choice. A named sound is picked from the ringtone list and saved; with no
     * name the choice is left open for the user, who knows what they want to hear.
     */
    private suspend fun alarmSound(list: ScreenView, row: AlarmRow, sound: String?, name: String): String? {
        suspend fun screen() = phone.observe()?.let(ScreenCompactor::compact)
        suspend fun press(view: ScreenView, pick: (Element) -> Boolean): ScreenView? {
            val e = view.elements.firstOrNull { it.enabled && it.kind != Kind.TEXT && pick(it) } ?: return null
            if (!phone.perform(view, AgentAction.Click(e.id))) return null
            delay(900)
            return screen()
        }
        // The row (not its switch) opens the alarm's editor.
        val item = list.elements.firstOrNull { it.kind == Kind.ITEM && it.bounds.centerY in list.element(row.id)!!.bounds.top..list.element(row.id)!!.bounds.bottom }
            ?: return null
        if (!phone.perform(list, AgentAction.Click(item.id))) return null
        delay(1000)
        val editor = screen() ?: return null
        val picker = press(editor) { Regex("^소리\\b").containsMatchIn(it.label.trim()) } ?: return null
        if (sound == null) return "$name 알람의 알람음 화면을 열었어요. 원하는 소리를 고른 뒤 뒤로 가서 저장을 눌러 주세요."
        val tones = press(picker) { it.label.contains("벨소리") } ?: return null
        // The clock app may ask for access to the phone's audio first: that answer is the user's.
        if (tones.snapshot.packageName.contains("permissioncontroller"))
            return "시계 앱이 음악·오디오 접근 권한을 묻고 있어요. 허용할지 직접 골라 주세요. 그다음 다시 말씀해 주시면 '$sound'을(를) 찾을게요."
        val wanted = GoalText.normalize(sound)
        // The ringtone list is long: page down through it until the name shows.
        var page = tones
        var tone: Element? = null
        for (i in 0..10) {
            tone = page.elements.firstOrNull { it.enabled && it.kind != Kind.TEXT && GoalText.normalize(it.label).contains(wanted) }
            if (tone != null) break
            val list = page.lists.maxByOrNull { it.bounds.height } ?: break
            val before = page.elements.map { it.label }
            phone.perform(page, AgentAction.Scroll(ScrollDir.DOWN, list.id)); delay(450)
            page = screen() ?: return null
            if (page.elements.map { it.label } == before) break
        }
        if (tone == null) return "'$sound' 알람음을 찾지 못했어요. 알람음 목록을 열어 두었어요."
        if (!phone.perform(page, AgentAction.Click(tone.id))) return null
        delay(600)
        // Back to the editor (the choice is kept), then save.
        repeat(3) {
            val now = screen() ?: return null
            now.elements.firstOrNull { it.enabled && it.kind == Kind.BUTTON && it.label.trim() == "저장" }?.let { save ->
                return if (phone.perform(now, AgentAction.Click(save.id))) "$name 알람음을 '${tone.label.trim()}'(으)로 바꿨어요." else null
            }
            phone.perform(now, AgentAction.Back); delay(700)
        }
        return null
    }

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

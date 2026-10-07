package dev.localphone.agent.runtime

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Process
import android.os.UserHandle
import android.provider.AlarmClock
import android.provider.Settings
import dev.localphone.core.*

/** Only public Android intents. App lists and resolved package IDs stay in the runtime. */
class AndroidDeviceTools(private val activity: Activity) : DevicePort {
    private fun inThisProfile(info: ActivityInfo): Boolean =
        info.packageName != "android" &&
            UserHandle.getUserHandleForUid(info.applicationInfo.uid) == Process.myUserHandle()

    fun apps(): List<AppCandidate> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        return activity.packageManager.queryIntentActivities(intent, 0).filter {
            it.activityInfo.packageName != activity.packageName && inThisProfile(it.activityInfo)
        }.map { info ->
            val id = info.activityInfo.packageName
            val aliases = when (id) {
                "com.google.android.deskclock", "com.android.deskclock" -> listOf("시계", "Clock")
                "com.android.settings" -> listOf("설정", "Settings")
                "com.google.android.calculator", "com.android.calculator2" -> listOf("계산기", "Calculator")
                NaverLinks.PACKAGE -> listOf("지도", "네이버지도", "네이버 지도", "Naver Map")
                "net.daum.android.map" -> listOf("지도", "카카오맵", "카카오 지도")
                "com.google.android.apps.maps" -> listOf("지도", "구글지도", "구글 지도", "Google Maps")
                else -> emptyList()
            }
            AppCandidate(id, info.loadLabel(activity.packageManager).toString(), aliases)
        }.distinctBy { it.id }
    }

    override fun prepare(action: Action.Device, selectedAppId: String?): DeviceCheck {
        val command = when (action) {
            is Action.OpenApp -> {
                val candidates = matchingApps(action.appName)
                val selected = if (selectedAppId != null) candidates.singleOrNull { it.id == selectedAppId } else candidates.singleOrNull()
                if (selected == null) return DeviceCheck.Blocked(
                    if (candidates.isEmpty()) "'${action.appName}'에 맞는 실행 가능한 앱을 찾지 못했습니다. 설치된 앱 이름을 그대로 말해 주세요."
                    else "같은 이름의 앱이 여러 개입니다. 어느 앱을 열까요?", candidates)
                PreparedDeviceCommand(action, "${selected.name} 열기", selected.id)
            }
            is Action.SetAlarm -> {
                if (action.hour !in 0..23 || action.minute !in 0..59) return DeviceCheck.Blocked("알람 시간 범위를 확인해 주세요.")
                PreparedDeviceCommand(action, "%02d:%02d 한 번 울리는 알람 설정".format(action.hour, action.minute))
            }
            is Action.SetTimer -> {
                if (action.seconds !in 1..86400) return DeviceCheck.Blocked("타이머 길이를 확인해 주세요.")
                val minutes = action.seconds / 60; val seconds = action.seconds % 60
                PreparedDeviceCommand(action, "${minutes}분 ${seconds}초 타이머 설정")
            }
            is Action.OpenSettings -> PreparedDeviceCommand(action, "${action.page.displayName} 열기")
        }
        return if (canExecute(command)) DeviceCheck.Ready(command)
        else DeviceCheck.Blocked("이 설치 공간에서 공식 Android 요청을 처리할 앱을 확정할 수 없습니다.")
    }

    fun localPackage(packageName: String): Boolean = runCatching {
        val info = activity.packageManager.getApplicationInfo(packageName, 0)
        packageName != activity.packageName && UserHandle.getUserHandleForUid(info.uid) == Process.myUserHandle()
    }.getOrDefault(false)

    fun matchingApps(name: String): List<AppCandidate> {
        val all = apps()
        val matched = AppNames.matching(name, all)
        // A conventional default, discovered from installed apps; no place registration is involved.
        if (PlaceText.normalize(name) == "지도") matched.singleOrNull { it.id == NaverLinks.PACKAGE }?.let { return listOf(it) }
        if (matched.isNotEmpty()) return matched
        val input = PlaceText.normalize(name)
        return all.filter { input.length >= 2 && PlaceText.normalize(it.name).contains(input) }
    }
    fun audioApps(): List<AppCandidate> = apps().filter { app ->
        runCatching { activity.packageManager.getApplicationInfo(app.id, 0).category == android.content.pm.ApplicationInfo.CATEGORY_AUDIO }.getOrDefault(false) ||
            Regex("음악|뮤직|music|spotify|clipstream|클립스트림|멜론|youtube music", RegexOption.IGNORE_CASE).containsMatchIn(app.name)
    }
    fun openPackage(packageName: String): Boolean = localPackage(packageName) && runCatching {
        val request = activity.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        request.component = localComponent(request) ?: return false
        activity.startActivity(request.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    }.getOrDefault(false)

    private fun intent(command: PreparedDeviceCommand): Intent? = when (val action = command.action) {
        is Action.OpenApp -> command.targetId?.let { activity.packageManager.getLaunchIntentForPackage(it) }
        is Action.SetAlarm -> Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, action.hour).putExtra(AlarmClock.EXTRA_MINUTES, action.minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "Local Phone Agent")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
        is Action.SetTimer -> Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, action.seconds)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "Local Phone Agent")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
        is Action.OpenSettings -> Intent(when (action.page) {
            SettingsPage.GENERAL -> Settings.ACTION_SETTINGS
            SettingsPage.WIFI -> Settings.ACTION_WIFI_SETTINGS
            SettingsPage.BLUETOOTH -> Settings.ACTION_BLUETOOTH_SETTINGS
            SettingsPage.SOUND -> Settings.ACTION_SOUND_SETTINGS
            SettingsPage.DISPLAY -> Settings.ACTION_DISPLAY_SETTINGS
        })
    }

    private fun localComponent(request: Intent): ComponentName? {
        @Suppress("DEPRECATION")
        val info = activity.packageManager.resolveActivity(request, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
            ?: return null
        // Never dispatch through the platform chooser or a cross-profile forwarder.
        if (!inThisProfile(info)) return null
        return ComponentName(info.packageName, info.name)
    }

    override fun canExecute(command: PreparedDeviceCommand): Boolean = runCatching {
        intent(command)?.let(::localComponent) != null
    }.getOrDefault(false)

    override fun dispatch(command: PreparedDeviceCommand): Boolean = runCatching {
        val request = intent(command) ?: return false
        request.component = localComponent(request) ?: return false
        request.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        activity.startActivity(request)
        true // A dispatch receipt, not evidence that the provider completed the requested task.
    }.getOrDefault(false)
}

package dev.localphone.agent.runtime

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.provider.Settings
import android.os.Process
import android.os.UserManager
import android.os.UserHandle
import dev.localphone.agent.data.SecureSettings
import dev.localphone.agent.BuildConfig
import dev.localphone.core.*

class NaverNavigation(private val activity: Activity) : NavigationPort {
    private fun pin(request: Intent): Intent? {
        @Suppress("DEPRECATION")
        val info = activity.packageManager.resolveActivity(request, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo ?: return null
        if (info.packageName != NaverLinks.PACKAGE || UserHandle.getUserHandleForUid(info.applicationInfo.uid) != Process.myUserHandle()) return null
        return request.setComponent(ComponentName(info.packageName, info.name))
    }
    private fun intent(destination: PlaceCandidate) = Intent(Intent.ACTION_VIEW,
        Uri.parse(NaverLinks.navigation(destination, activity.packageName)))
        .addCategory(Intent.CATEGORY_BROWSABLE).setPackage(NaverLinks.PACKAGE)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    override fun canLaunch(destination: PlaceCandidate) = pin(intent(destination)) != null
    override fun launch(destination: PlaceCandidate) = runCatching {
        if (!canLaunch(destination)) return false
        activity.startActivity(pin(intent(destination)) ?: return false); true
    }.getOrDefault(false)
    fun openSearch(query: String) = runCatching {
        val target = Intent(Intent.ACTION_VIEW, Uri.parse(NaverLinks.search(query, activity.packageName)))
            .setPackage(NaverLinks.PACKAGE).addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        activity.startActivity(pin(target) ?: return false); true
    }.getOrDefault(false)
    fun openMap() = runCatching {
        val request = Intent(Intent.ACTION_VIEW, Uri.parse("nmap://map?appname=${activity.packageName}"))
            .setPackage(NaverLinks.PACKAGE).addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        activity.startActivity(pin(request) ?: return false); true
    }.getOrDefault(false)
    fun openStore() = runCatching {
        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${NaverLinks.PACKAGE}")))
    }
}

class AndroidMedia(private val activity: Activity, private val settings: SecureSettings) : MediaPort {
    val availableInThisBuild get() = BuildConfig.MEDIA_SESSION_CONTROL_AVAILABLE
    private val component = ComponentName(activity.packageName, "${activity.packageName}.runtime.AgentNotificationListener")
    fun hasPermission(): Boolean {
        if (!availableInThisBuild) return false
        return Settings.Secure.getString(activity.contentResolver, "enabled_notification_listeners")
            .orEmpty().split(':').any { ComponentName.unflattenFromString(it) == component }
    }
    fun controllers(): List<MediaController> {
        if (!hasPermission()) return emptyList()
        return runCatching {
            activity.getSystemService(MediaSessionManager::class.java).getActiveSessions(component)
                .filter { controller ->
                    // MediaSessionManager is queried in this installation's user, never its parent profile.
                    controller.packageName != NaverLinks.PACKAGE &&
                        runCatching { UserHandle.getUserHandleForUid(activity.packageManager.getApplicationInfo(controller.packageName, 0).uid) ==
                            Process.myUserHandle() }.getOrDefault(false) &&
                        (controller.playbackState?.actions ?: 0L) and
                            (PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT) != 0L
                }.distinctBy { it.packageName }
        }.getOrDefault(emptyList())
    }
    fun selectedController(): MediaController? {
        val sessions = controllers()
        val selected = settings.get("music_package")
        return if (selected.isNotBlank()) sessions.singleOrNull { it.packageName == selected }
        else sessions.singleOrNull()
    }
    override fun resume(): Boolean = runCatching {
        val selected = selectedController()?.takeIf { supported(it, MediaCommand.RESUME) } ?: return false
        selected.transportControls.play()
        true // This reports dispatch; the music app owns actual playback/audio focus.
    }.getOrDefault(false)
    override fun canExecute(command: MediaCommand) = selectedController()?.let { supported(it, command) } == true
    override fun pause(): Boolean = runCatching {
        val selected = selectedController()?.takeIf { supported(it, MediaCommand.PAUSE) } ?: return false
        selected.transportControls.pause(); true
    }.getOrDefault(false)
    override fun next(): Boolean = runCatching {
        val selected = selectedController()?.takeIf { supported(it, MediaCommand.NEXT) } ?: return false
        selected.transportControls.skipToNext(); true
    }.getOrDefault(false)
    private fun supported(controller: MediaController, command: MediaCommand): Boolean {
        val required = when (command) {
            MediaCommand.RESUME -> PlaybackState.ACTION_PLAY
            MediaCommand.PAUSE -> PlaybackState.ACTION_PAUSE
            MediaCommand.NEXT -> PlaybackState.ACTION_SKIP_TO_NEXT
        }
        return (controller.playbackState?.actions ?: 0L) and required != 0L
    }
    fun permissionMessage(): String = if (!availableInThisBuild)
        "이 설치판은 음악 직접 제어를 지원하지 않습니다. 음성·앱 열기·알람·타이머·내비를 사용할 수 있습니다."
        else if (android.os.Build.VERSION.SDK_INT >= 30 &&
        activity.getSystemService(UserManager::class.java).isManagedProfile)
        "이 프로필에서 알림 접근을 사용할 수 없습니다. 보안폴더에서 음악 제어 지원 여부를 확인해 주세요."
        else "음악 제어 연결 권한이 필요합니다. 보안폴더가 이 권한을 차단하면 음악 제어를 사용할 수 없습니다."
    fun choose(packageName: String) = settings.put("music_package", packageName)
    fun permissionIntent(): Intent {
        check(availableInThisBuild) { "Music control is unavailable in this build" }
        return Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    }
}

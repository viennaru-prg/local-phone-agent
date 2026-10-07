package dev.localphone.agent.runtime

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.UserManager

/** Public APIs only. A managed profile label is not proof that this is Samsung Secure Folder. */
class ProfileScope(private val context: Context) {
    val user get() = Process.myUserHandle().toString()
    val isManagedProfile get() = Build.VERSION.SDK_INT >= 30 && context.getSystemService(UserManager::class.java).isManagedProfile
    fun canAct(): Boolean = runCatching {
        val users = context.getSystemService(UserManager::class.java)
        val guard = context.getSystemService(KeyguardManager::class.java)
        users.isUserUnlocked && !guard.isDeviceLocked && !guard.isKeyguardLocked &&
            context.getSystemService(PowerManager::class.java).isInteractive
    }.getOrDefault(false)
    fun description() = "이 설치 공간의 앱과 데이터만 사용합니다. 보안폴더 안에서 사용하려면 이 앱과 대상 앱을 그 안에 추가하세요."
}

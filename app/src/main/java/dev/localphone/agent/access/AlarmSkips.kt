package dev.localphone.agent.access

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * "다음 알람 꺼줘" for a repeating alarm: the clock app has no "skip once", so the alarm is switched off
 * and switched back on right after the skipped ring. Samsung Clock turns an existing alarm with the
 * same time, days and name back on when asked to set it again (no duplicate). A skip is kept until it
 * is done: the wake-up shortly after the ring, every unlock and every command retry it until it went
 * through, so a locked phone at that moment does not leave the alarm off for good.
 */
object AlarmSkips {
    private const val TAG = "AgentAlarm"
    private const val PREFS = "alarm_skips"

    /** [sent]: when the re-enable was last sent (0 = not yet). The skip is kept until the list shows the alarm on. */
    data class Skip(val hour: Int, val minute: Int, val days: List<Int>, val label: String?, val after: Long, val sent: Long = 0)

    fun schedule(context: Context, skip: Skip) {
        save(context, load(context) + skip)
        val at = skip.after + 60_000L
        val pending = PendingIntent.getBroadcast(context, (skip.hour * 60 + skip.minute), Intent(context, Receiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        // Inexact is enough: anywhere in the 10 minutes after the skipped ring, long before the next one.
        context.getSystemService(AlarmManager::class.java).setWindow(AlarmManager.RTC_WAKEUP, at, 10 * 60_000L, pending)
        Log.i(TAG, "skip ${skip.hour}:${skip.minute} days=${skip.days} until ${java.util.Date(at)}")
    }

    /** A deleted alarm must not come back: its pending skip is dropped. */
    fun forget(context: Context, hour: Int, minute: Int, days: List<Int>, label: String?) =
        save(context, load(context).filterNot { it.hour == hour && it.minute == minute && it.days == days && it.label == label })

    /**
     * Turns back on every skipped alarm whose ring has passed. Sending is not proof: the skip stays until
     * [verify] sees the alarm on in the clock app's list, and is sent again every 10 minutes until then.
     */
    fun catchUp(context: Context) {
        val now = System.currentTimeMillis()
        val skips = load(context)
        if (skips.none { it.after < now }) return
        save(context, skips.map { s ->
            if (s.after < now && now - s.sent > 10 * 60_000L && reenable(context, s)) {
                if (s.sent == 0L) notify(context, s)
                s.copy(sent = now)
            } else s
        })
    }

    /**
     * The clock app's list was read: a skipped alarm seen on again after its ring is done; one still off
     * after its ring is sent again right away.
     */
    fun verify(context: Context, rows: List<dev.localphone.core.AlarmRow>) {
        val now = System.currentTimeMillis()
        val skips = load(context)
        if (skips.isEmpty()) return
        val left = skips.filterNot { s ->
            val row = rows.firstOrNull { it.hour == s.hour && it.minute == s.minute && it.days == s.days && it.label == s.label }
            when {
                row == null -> s.after < now          // deleted meanwhile: nothing to restore
                s.after >= now -> false               // still skipping
                row.on -> { Log.i(TAG, "verified ${s.hour}:${s.minute} back on"); true }
                else -> { reenable(context, s); false }
            }
        }
        if (left.size != skips.size) save(context, left)
    }

    private fun notify(context: Context, s: Skip) = runCatching {
        val time = dev.localphone.core.AlarmCommands.clock(s.hour, s.minute)
        val n = android.app.Notification.Builder(context, dev.localphone.agent.AgentApp.CHANNEL)
            .setSmallIcon(dev.localphone.agent.R.drawable.ic_agent).setContentTitle("알람을 다시 켰어요")
            .setContentText("한 번 건너뛴 $time 알람을 다시 켜도록 했어요. 시계 앱에서 켜져 있는지 확인해 주세요.")
            .setContentIntent(PendingIntent.getActivity(context, 0, Intent(AlarmClock.ACTION_SHOW_ALARMS), PendingIntent.FLAG_IMMUTABLE))
            .setAutoCancel(true).build()
        context.getSystemService(android.app.NotificationManager::class.java).notify(7000 + s.hour * 60 + s.minute, n)
    }

    private fun reenable(context: Context, skip: Skip): Boolean = runCatching {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, skip.hour).putExtra(AlarmClock.EXTRA_MINUTES, skip.minute)
            .putExtra(AlarmClock.EXTRA_DAYS, ArrayList(skip.days)).putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        skip.label?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        // The accessibility service may start activities from the background; a plain receiver may not.
        (AgentAccessibilityService.instance ?: context).startActivity(intent)
        Log.i(TAG, "re-enabled ${skip.hour}:${skip.minute}")
        true
    }.onFailure { Log.w(TAG, "re-enable ${skip.hour}:${skip.minute}", it) }.getOrDefault(false)

    private fun load(context: Context): List<Skip> = runCatching {
        val json = JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("skips", "[]"))
        (0 until json.length()).map { i ->
            val o = json.getJSONObject(i)
            val days = o.getJSONArray("days").let { d -> (0 until d.length()).map { d.getInt(it) } }
            Skip(o.getInt("hour"), o.getInt("minute"), days, o.optString("label").ifEmpty { null }, o.getLong("after"), o.optLong("sent"))
        }
    }.getOrDefault(emptyList())

    private fun save(context: Context, skips: List<Skip>) {
        val json = JSONArray(skips.map { s ->
            JSONObject().put("hour", s.hour).put("minute", s.minute).put("days", JSONArray(s.days)).put("label", s.label ?: "").put("after", s.after).put("sent", s.sent)
        })
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("skips", json.toString()).apply()
    }

    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = catchUp(context)
    }
}

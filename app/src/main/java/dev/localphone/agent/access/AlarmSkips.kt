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

    data class Skip(val hour: Int, val minute: Int, val days: List<Int>, val label: String?, val after: Long)

    fun schedule(context: Context, skip: Skip) {
        save(context, load(context) + skip)
        val at = skip.after + 60_000L
        val pending = PendingIntent.getBroadcast(context, (skip.hour * 60 + skip.minute), Intent(context, Receiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        // Inexact is enough: anywhere in the 10 minutes after the skipped ring, long before the next one.
        context.getSystemService(AlarmManager::class.java).setWindow(AlarmManager.RTC_WAKEUP, at, 10 * 60_000L, pending)
        Log.i(TAG, "skip ${skip.hour}:${skip.minute} days=${skip.days} until ${java.util.Date(at)}")
    }

    /** Turns back on every skipped alarm whose ring has passed. */
    fun catchUp(context: Context) {
        val now = System.currentTimeMillis()
        val (due, waiting) = load(context).partition { it.after < now }
        if (due.isEmpty()) return
        val left = due.filterNot { reenable(context, it) }
        save(context, waiting + left)
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
            Skip(o.getInt("hour"), o.getInt("minute"), days, o.optString("label").ifEmpty { null }, o.getLong("after"))
        }
    }.getOrDefault(emptyList())

    private fun save(context: Context, skips: List<Skip>) {
        val json = JSONArray(skips.map { s ->
            JSONObject().put("hour", s.hour).put("minute", s.minute).put("days", JSONArray(s.days)).put("label", s.label ?: "").put("after", s.after)
        })
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("skips", json.toString()).apply()
    }

    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = catchUp(context)
    }
}

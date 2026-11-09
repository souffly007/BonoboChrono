// SPDX-License-Identifier: GPL-3.0-or-later
package fr.bonobo.chrono

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/** Wall-clock alarms survive process death and reboot. Days use Calendar.SUNDAY..SATURDAY. */
data class ScheduledAlarm(val id: Int, val hour: Int, val minute: Int,
                          val days: Set<Int> = emptySet(), val enabled: Boolean = true) {
    fun nextAfter(now: Long): Long {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        repeat(8) {
            if (calendar.timeInMillis > now && (days.isEmpty() || calendar.get(Calendar.DAY_OF_WEEK) in days))
                return calendar.timeInMillis
            calendar.add(Calendar.DAY_OF_YEAR, 1)
        }
        error("No next occurrence")
    }
}

class ScheduledAlarmStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("scheduled_alarms", Context.MODE_PRIVATE)
    private val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun all(): List<ScheduledAlarm> = try {
        val json = JSONArray(prefs.getString("items", "[]"))
        (0 until json.length()).map { i ->
            val obj = json.getJSONObject(i)
            val days = obj.optJSONArray("days") ?: JSONArray()
            ScheduledAlarm(obj.getInt("id"), obj.getInt("hour"), obj.getInt("minute"),
                (0 until days.length()).map { days.getInt(it) }.toSet(), obj.optBoolean("enabled", true))
        }.filter { it.hour in 0..23 && it.minute in 0..59 && it.days.all { day -> day in 1..7 } }
    } catch (e: Exception) {
        Log.w("BonoboAlarm", "Invalid saved alarms", e)
        emptyList()
    }

    fun save(alarm: ScheduledAlarm) {
        val items = all().filterNot { it.id == alarm.id } + alarm
        write(items)
        schedule(alarm)
    }

    fun delete(id: Int) {
        cancel(id)
        write(all().filterNot { it.id == id })
    }

    private fun write(items: List<ScheduledAlarm>) {
        val json = JSONArray()
        items.forEach { a -> json.put(JSONObject().put("id", a.id).put("hour", a.hour)
            .put("minute", a.minute).put("enabled", a.enabled)
            .put("days", JSONArray().apply { a.days.sorted().forEach { put(it) } })) }
        prefs.edit().putString("items", json.toString()).commit()
    }

    private fun pending(id: Int, at: Long = 0L): PendingIntent = PendingIntent.getBroadcast(context, id,
        Intent(context, ScheduledAlarmReceiver::class.java).setAction(ACTION_FIRE)
            .putExtra("id", id).putExtra("at", at),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun cancel(id: Int) {
        manager.cancel(pending(id))
        prefs.edit().remove("next.$id").apply()
    }

    fun isCurrent(id: Int, at: Long) = at > 0 && prefs.getLong("next.$id", 0L) == at

    fun schedule(alarm: ScheduledAlarm) {
        cancel(alarm.id)
        if (!alarm.enabled) return
        val at = alarm.nextAfter(System.currentTimeMillis())
        prefs.edit().putLong("next.${alarm.id}", at).commit()
        val show = PendingIntent.getActivity(context, alarm.id,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        try {
            if (Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms())
                manager.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pending(alarm.id, at))
            else scheduleInexact(at, alarm.id)
        } catch (e: SecurityException) {
            Log.w("BonoboAlarm", "Exact alarm permission unavailable", e)
            scheduleInexact(at, alarm.id)
        }
    }

    private fun scheduleInexact(at: Long, id: Int) {
        if (Build.VERSION.SDK_INT >= 23)
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(id, at))
        else manager.set(AlarmManager.RTC_WAKEUP, at, pending(id, at))
    }

    fun rescheduleAll() {
        all().forEach { alarm ->
            if (alarm.enabled && alarm.days.isEmpty() &&
                prefs.getLong("next.${alarm.id}", Long.MAX_VALUE) <= System.currentTimeMillis())
                save(alarm.copy(enabled = false))
            else schedule(alarm)
        }
    }

    companion object { const val ACTION_FIRE = "fr.bonobo.chrono.SCHEDULED_FIRE" }
}

class ScheduledAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ScheduledAlarmStore.ACTION_FIRE) return
        val id = intent.getIntExtra("id", -1)
        val store = ScheduledAlarmStore(context)
        if (!store.isCurrent(id, intent.getLongExtra("at", 0L))) return
        val alarm = store.all().firstOrNull { it.id == id && it.enabled } ?: return
        // The next occurrence is installed before sound starts, including for repeating alarms.
        if (alarm.days.isEmpty()) store.save(alarm.copy(enabled = false)) else store.schedule(alarm)
        val service = Intent(context, ChronoService::class.java)
            .setAction(ChronoService.ACTION_SCHEDULED).putExtra("id", id)
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(service)
        else context.startService(service)
    }
}

class AlarmBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            intent.action == Intent.ACTION_TIME_CHANGED ||
            intent.action == Intent.ACTION_TIMEZONE_CHANGED ||
            intent.action == AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)
            ScheduledAlarmStore(context).rescheduleAll()
    }
}

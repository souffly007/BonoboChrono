// SPDX-License-Identifier: GPL-3.0-or-later
package fr.bonobo.chrono

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*

fun fmtTime(ms: Long): String {
    val positive = ms.coerceAtLeast(0)
    val s = positive / 1000 + if (positive % 1000 != 0L) 1 else 0
    return "%02d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
}

class ChronoService : Service() {
    data class S(
        val classic: ClassicState = ClassicState(),
        val running: Boolean = false,
        val paused: Boolean = false,
        val finished: Boolean = false,
        val phase: Int = 0,
        val cycle: Int = 0,
        val total: Int = 0,
        val remaining: Long = 0,
        val d1: Long = 0,
        val d2: Long = 0,
        val alarms: Map<String, String> = emptyMap()
    )

    companion object {
        const val ACTION_CLASSIC = "fr.bonobo.chrono.CLASSIC"
        const val ACTION_WAKE = "fr.bonobo.chrono.WAKE"
        const val ACTION_START = "fr.bonobo.chrono.START"
        const val ACTION_STOP = "fr.bonobo.chrono.STOP"
        const val ACTION_PAUSE = "fr.bonobo.chrono.PAUSE"
        const val ACTION_RESUME = "fr.bonobo.chrono.RESUME"
        const val ACTION_STOP_ALARM = "fr.bonobo.chrono.STOP_ALARM"
        const val ACTION_SCHEDULED = "fr.bonobo.chrono.SCHEDULED"
        const val EXTRA_D1 = "d1"
        const val EXTRA_D2 = "d2"
        const val EXTRA_TOTAL = "total"
        const val EXTRA_TOKEN = "token"
        const val EXTRA_ALARM = "alarm"
        const val INTERVAL_ALARM = "interval"
        const val COUNTDOWN_ALARM = "countdown"
        fun scheduledKey(id: Int) = "scheduled.$id"
        private const val CHANNEL = "chrono"
        private const val NOTIF_ID = 1

        @Volatile var state = S()
            private set
        var listener: ((S) -> Unit)? = null

        fun stopAlarm(context: Context, key: String) {
            val token = state.alarms[key] ?: return
            context.startService(Intent(context, ChronoService::class.java)
                .setAction(ACTION_STOP_ALARM).putExtra(EXTRA_ALARM, key).putExtra(EXTRA_TOKEN, token))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var session: IntervalSession? = null
    private var pausedSession: IntervalSession? = null
    private var countdownToken: String? = null
    private var countdownDeadline = 0L
    private var wake: PowerManager.WakeLock? = null
    private lateinit var alarms: AlarmController
    private var foreground = false
    private var lastNotification = ""
    private val alarmIntents = mutableMapOf<String, Pair<String, PendingIntent>>()
    private var destroying = false
    private val prefs by lazy { getSharedPreferences("active_timers", MODE_PRIVATE) }
    private var scheduledDeadline = 0L
    private var lastPersisted: List<Any?>? = null
    private var restoringState = false
    private val deadlineIntent by lazy {
        val intent = Intent(this, TimerReceiver::class.java).setAction(ACTION_WAKE)
        PendingIntent.getBroadcast(this, 90, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun persist() {
        if (restoringState) return
        val c = state.classic
        val signature = listOf(c, session, pausedSession, countdownToken, countdownDeadline,
            state.finished, state.phase, state.cycle, state.d1, state.d2, state.total, alarms.snapshots())
        if (signature == lastPersisted) return
        lastPersisted = signature
        val e = prefs.edit().clear()
            .putInt("boot", android.provider.Settings.Global.getInt(contentResolver, "boot_count", -1))
            .putBoolean("down", c.down).putBoolean("classicRunning", c.running)
            .putBoolean("done", c.done).putLong("base", c.base)
            .putLong("startAt", c.startAt).putLong("target", c.target)
            .putString("countdownToken", countdownToken).putLong("deadline", countdownDeadline)
            .putBoolean("finished", state.finished)
            .putBoolean("intervalRunning", session != null)
            .putBoolean("intervalPaused", pausedSession != null)
            .putLong("d1", state.d1).putLong("d2", state.d2).putInt("total", state.total)
            .putInt("phase", state.phase).putInt("cycle", state.cycle)
        (session ?: pausedSession)?.let {
            e.putLong("d1", it.d1).putLong("d2", it.d2).putInt("total", it.total)
                .putLong("startedAt", it.startedAt)
                .putLong("elapsedBeforeStart", it.elapsedBeforeStart)
        }
        alarms.snapshots().forEach { (key, alarm) ->
            e.putString("alarm.$key", alarm.token).putLong("alarmEnd.$key", alarm.endsAt)
                .putInt("beep.$key", alarm.beepMs).putInt("gap.$key", alarm.gapMs)
        }
        e.commit()
    }

    private fun restore() {
        if (!prefs.contains("boot")) return
        val boot = android.provider.Settings.Global.getInt(contentResolver, "boot_count", -1)
        if (prefs.getInt("boot", -1) != boot || prefs.getLong("startAt", 0) > SystemClock.elapsedRealtime()) {
            prefs.edit().clear().commit(); return
        }
        state = S(classic = ClassicState(prefs.getBoolean("down", true),
            prefs.getBoolean("classicRunning", false), prefs.getBoolean("done", false),
            prefs.getLong("base", 0), prefs.getLong("startAt", 0), prefs.getLong("target", 0)),
            finished = prefs.getBoolean("finished", false),
            d1 = prefs.getLong("d1", 0), d2 = prefs.getLong("d2", 0),
            total = prefs.getInt("total", 0), phase = prefs.getInt("phase", 0),
            cycle = prefs.getInt("cycle", 0))
        val d1 = prefs.getLong("d1", 0); val d2 = prefs.getLong("d2", 0)
        val total = prefs.getInt("total", 0)
        if (validSession(d1, d2, total) &&
            (prefs.getBoolean("intervalRunning", false) || prefs.getBoolean("intervalPaused", false))) {
            val timing = IntervalSession(d1, d2, total, prefs.getLong("startedAt", 0),
                prefs.getLong("elapsedBeforeStart", 0))
            if (prefs.getBoolean("intervalRunning", false)) session = timing else pausedSession = timing
            val position = timing.position(if (session != null) SystemClock.elapsedRealtime() else timing.startedAt)
            state = state.copy(running = session != null, paused = pausedSession != null,
                d1 = d1, d2 = d2, total = total, phase = position.phase,
                cycle = position.cycle, remaining = position.remaining)
        }
        countdownToken = prefs.getString("countdownToken", null)
        countdownDeadline = prefs.getLong("deadline", 0)
        // Start foreground before restoring sound or processing an overdue deadline.
        val savedKeys = prefs.all.keys.filter { it.startsWith("alarm.") }
            .map { it.removePrefix("alarm.") }
        if (session != null || state.classic.running || savedKeys.isNotEmpty()) ensureForeground()
        restoringState = true
        savedKeys.forEach { key ->
            prefs.getString("alarm.$key", null)?.let { token ->
                alarms.restore(key, token, prefs.getLong("alarmEnd.$key", 0),
                    prefs.getInt("beep.$key", 500), prefs.getInt("gap.$key", 400))
            }
        }
        restoringState = false
    }

    private fun scheduleDeadline() {
        val now = SystemClock.elapsedRealtime()
        val next = listOfNotNull(session?.position(now)?.remaining?.let { now + it },
            countdownToken?.let { countdownDeadline }).minOrNull() ?: 0L
        if (next == scheduledDeadline) return
        val manager = getSystemService(ALARM_SERVICE) as AlarmManager
        manager.cancel(deadlineIntent)
        scheduledDeadline = next
        if (next == 0L) return
        if (Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms()) {
            // AlarmClock wakes from Doze without the per-app idle-alarm frequency limit.
            val show = PendingIntent.getActivity(this, 91, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            manager.setAlarmClock(AlarmManager.AlarmClockInfo(System.currentTimeMillis() +
                (next - now).coerceAtLeast(1), show), deadlineIntent)
        } else manager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, next, deadlineIntent)
    }

    private fun classicCommand(i: Intent) {
        val c = state.classic
        val now = SystemClock.elapsedRealtime()
        when (i.getStringExtra("command")) {
            "pause" -> {
                state = state.copy(classic = c.copy(running = false, base = c.elapsed(now)))
                countdownToken = null
            }
            "reset" -> {
                countdownToken = null
                alarms.stop(COUNTDOWN_ALARM, alarms.active[COUNTDOWN_ALARM])
                state = state.copy(classic = ClassicState(down = i.getBooleanExtra("down", c.down)))
            }
            "start" -> {
                if (c.running) return
                val down = i.getBooleanExtra("down", c.down)
                val duration = i.getLongExtra("duration", c.target)
                if (down && duration !in 1..MAX_DURATION_MS) return
                val base = if (c.done) 0L else c.base
                state = state.copy(classic = ClassicState(down, true, false, base, now, duration))
                alarms.stop(COUNTDOWN_ALARM, alarms.active[COUNTDOWN_ALARM])
                if (down) {
                    countdownToken = java.util.UUID.randomUUID().toString()
                    countdownDeadline = now + duration - base
                }
                ensureForeground()
            }
        }
    }
    private val tick = object : Runnable {
        override fun run() {
            advance()
            settle()
            if (session != null || state.classic.running || countdownToken != null || alarms.active.isNotEmpty())
                handler.postDelayed(this, nextTickDelay())
        }
    }

    private fun nextTickDelay(): Long {
        if (session == null && !state.classic.running && countdownToken == null) return 30_000L
        val now = SystemClock.elapsedRealtime()
        val phaseLeft = session?.position(now)?.remaining ?: 250L
        val countdownLeft = if (countdownToken != null) countdownDeadline - now else 250L
        return minOf(250L, phaseLeft.coerceAtLeast(1), countdownLeft.coerceAtLeast(1))
    }

    override fun onCreate() {
        super.onCreate()
        alarms = AlarmController(this) {
            if (!destroying) {
                publish()
                updateNotif()
                // Defer settling until an alarm replacement or transition is complete.
                handler.post { if (!destroying) settle() }
            }
        }
        restore()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CLASSIC -> { advance(); classicCommand(intent) }
            ACTION_WAKE -> { scheduledDeadline = -1L; ensureForeground(); advance() }
            ACTION_SCHEDULED -> {
                val id = intent.getIntExtra("id", -1)
                if (id >= 0) {
                    ensureForeground()
                    alarms.startFinal(scheduledKey(id), java.util.UUID.randomUUID().toString(), 500, 400)
                    holdWake(AlarmController.FINAL_ALARM_TIMEOUT_MS)
                }
            }
            ACTION_START -> start(intent)
            ACTION_PAUSE -> pauseInterval()
            ACTION_RESUME -> resumeInterval()
            ACTION_STOP -> {
                session = null
                pausedSession = null
                state = S(classic = state.classic, alarms = alarms.active)
                alarms.stop(INTERVAL_ALARM, alarms.active[INTERVAL_ALARM])
            }
            ACTION_STOP_ALARM -> alarms.stop(intent.getStringExtra(EXTRA_ALARM) ?: "",
                intent.getStringExtra(EXTRA_TOKEN))
        }
        advance()
        publish()
        handler.removeCallbacks(tick)
        if (session != null || state.classic.running || countdownToken != null || alarms.active.isNotEmpty()) handler.post(tick)
        updateNotif()
        settle()
        return START_STICKY
    }

    private fun start(i: Intent) {
        if (session != null || pausedSession != null) return
        val d1 = i.getLongExtra(EXTRA_D1, 0)
        val d2 = i.getLongExtra(EXTRA_D2, 0)
        val total = i.getIntExtra(EXTRA_TOTAL, 0)
        if (!validSession(d1, d2, total)) return
        session = IntervalSession(d1, d2, total, SystemClock.elapsedRealtime())
        state = S(classic = state.classic, running = true, phase = 1, cycle = 1, total = total,
            remaining = d1, d1 = d1, d2 = d2, alarms = alarms.active)
        alarms.stop(INTERVAL_ALARM, alarms.active[INTERVAL_ALARM])
        ensureForeground()
        holdWake()
    }

    private fun pauseInterval() {
        val timing = session ?: return
        val now = SystemClock.elapsedRealtime()
        val elapsed = (timing.elapsedBeforeStart + now - timing.startedAt)
            .coerceIn(0, (timing.d1 + timing.d2) * timing.total)
        pausedSession = timing.copy(startedAt = now, elapsedBeforeStart = elapsed)
        session = null
        state = state.copy(running = false, paused = true)
    }

    private fun resumeInterval() {
        val timing = pausedSession ?: return
        session = timing.copy(startedAt = SystemClock.elapsedRealtime())
        pausedSession = null
        state = state.copy(running = true, paused = false)
        ensureForeground()
        holdWake()
    }

    private fun advance() {
        val now = SystemClock.elapsedRealtime()
        session?.let { timing ->
            val position = timing.position(now)
            val transitioned = position.phase != state.phase || position.cycle != state.cycle
            state = state.copy(running = !position.finished, finished = position.finished,
                phase = position.phase, cycle = position.cycle, remaining = position.remaining)
            if (position.finished) session = null
            // After a delayed callback, emit only the current transition, never missed alarms.
            if (transitioned) {
                val token = java.util.UUID.randomUUID().toString()
                if (position.finished) {
                    alarms.startFinal(INTERVAL_ALARM, token, 1200, 400)
                    holdWake(AlarmController.FINAL_ALARM_TIMEOUT_MS)
                } else alarms.start(INTERVAL_ALARM, token, 1, 300, 0)
            }
        }
        if (countdownToken != null && now >= countdownDeadline) {
            val token = countdownToken!!
            countdownToken = null
            state = state.copy(classic = state.classic.copy(running = false, done = true, base = 0))
            alarms.startFinal(COUNTDOWN_ALARM, token, 500, 400)
            holdWake(AlarmController.FINAL_ALARM_TIMEOUT_MS)
        }
        publish()
        scheduleDeadline()
        updateNotif()
        if (session != null || state.classic.running || alarms.active.isNotEmpty()) holdWake(60_000)
    }

    private fun holdWake(minRemaining: Long = 0) {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val lock = wake ?: pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
            "fr.bonobo.chrono:timer").apply { setReferenceCounted(false) }.also { wake = it }
        val now = SystemClock.elapsedRealtime()
        val intervalLeft = session?.let { (it.d1 + it.d2) * it.total -
            it.elapsedBeforeStart - (now - it.startedAt) } ?: 0
        val countdownLeft = if (countdownToken != null) countdownDeadline - now else 0
        lock.acquire(maxOf(intervalLeft, countdownLeft, minRemaining) + 60_000L)
    }

    private fun settle() {
        if (session != null || state.classic.running || countdownToken != null || alarms.active.isNotEmpty()) return
        handler.removeCallbacks(tick)
        wake?.let { if (it.isHeld) it.release() }; wake = null
        if (foreground) {
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
            else @Suppress("DEPRECATION") stopForeground(true)
            foreground = false
        }
        stopSelf()
    }

    private fun publish() {
        state = state.copy(alarms = alarms.active)
        persist()
        val iterator = alarmIntents.iterator()
        while (iterator.hasNext()) {
            val (key, entry) = iterator.next()
            if (state.alarms[key] != entry.first) {
                entry.second.cancel()
                iterator.remove()
            }
        }
        listener?.invoke(state)
    }

    private fun ensureForeground() {
        createChannel()
        val notification = buildNotif(notificationText())
        if (Build.VERSION.SDK_INT >= 34)
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIF_ID, notification)
        foreground = true
        lastNotification = ""
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Chrono", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun notificationText(): String {
        val parts = mutableListOf<String>()
        if (alarms.active.isNotEmpty()) parts += "Alarme active · Arrêter l’alarme"
        if (state.running) parts += "Phase ${state.phase} en cours · ${state.cycle}/${state.total}"
        if (state.paused) parts += "Enchaînement en pause"
        if (countdownToken != null) parts += "Minuteur en cours"
        if (state.classic.running && !state.classic.down)
            parts += "Chronomètre en cours"
        return parts.joinToString(" — ").ifEmpty { "Terminé" }
    }

    /** Stable monotonic anchor: elapsed time never changes the notification signature. */
    private fun notificationClock(): Pair<Long, Boolean>? {
        if (alarms.active.isNotEmpty()) return null
        val now = SystemClock.elapsedRealtime()
        val clock = when {
            state.running -> session?.let { (now + it.position(now).remaining) to true }
            countdownToken != null -> countdownDeadline to true
            state.classic.running -> (state.classic.startAt - state.classic.base) to false
            else -> null
        }
        // Native countdown direction is available from Android 7. Older devices show status only.
        return clock?.takeUnless { it.second && Build.VERSION.SDK_INT < 24 }
    }

    private fun updateNotif() {
        if (!foreground) return
        val text = notificationText()
        val signature = "$text|${state.running}|${state.classic.running}|${notificationClock()}|${alarms.active}"
        if (signature == lastNotification) return
        lastNotification = signature
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotif(text))
    }

    private fun buildNotif(text: String): Notification {
        val imm = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or imm
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        @Suppress("DEPRECATION")
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL)
                else Notification.Builder(this)
        b.setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Bonobo Chrono").setContentText(text)
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open)
            .setShowWhen(false)
        if (state.running || state.paused) {
            val stop = PendingIntent.getService(this, 1,
                Intent(this, ChronoService::class.java).setAction(ACTION_STOP), flags)
            @Suppress("DEPRECATION")
            b.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Arrêter", stop)
        }
        if (state.running || state.paused) {
            val action = if (state.running) ACTION_PAUSE else ACTION_RESUME
            val pause = PendingIntent.getService(this, 4,
                Intent(this, ChronoService::class.java).setAction(action), flags)
            @Suppress("DEPRECATION")
            b.addAction(android.R.drawable.ic_media_pause, if (state.running) "Pause" else "Reprendre", pause)
        }
        if (state.classic.running) {
            val pause = PendingIntent.getService(this, 3, Intent(this, ChronoService::class.java)
                .setAction(ACTION_CLASSIC).putExtra("command", "pause"), flags)
            @Suppress("DEPRECATION")
            b.addAction(android.R.drawable.ic_media_pause, "Pause", pause)
        }
        alarms.active.forEach { (key, token) ->
            // Unique data prevents an old PendingIntent being retargeted to a new alarm.
            val intent = Intent(this, ChronoService::class.java).setAction(ACTION_STOP_ALARM)
                .setData(android.net.Uri.parse("bonobochrono://alarm/$key/$token"))
                .putExtra(EXTRA_ALARM, key).putExtra(EXTRA_TOKEN, token)
            val stop = alarmIntents[key]?.takeIf { it.first == token }?.second
                ?: PendingIntent.getService(this, 2, intent, imm).also {
                    alarmIntents[key] = token to it
                }
            val label = if (alarms.active.size > 1) {
                when {
                    key == COUNTDOWN_ALARM -> "Arrêter alarme décompte"
                    key == INTERVAL_ALARM -> "Arrêter alarme enchaînement"
                    else -> "Arrêter alarme programmée"
                }
            } else "Arrêter l’alarme"
            @Suppress("DEPRECATION")
            b.addAction(android.R.drawable.ic_lock_silent_mode, label, stop)
        }
        notificationClock()?.let { (anchor, down) ->
            // Convert the absolute monotonic deadline to Notification.when's wall-clock time.
            // SystemUI owns every subsequent displayed tick, including in background.
            val remaining = anchor - SystemClock.elapsedRealtime()
            b.setWhen(System.currentTimeMillis() + remaining)
                .setShowWhen(true)
                .setUsesChronometer(true)
            if (Build.VERSION.SDK_INT >= 24) b.setChronometerCountDown(down)
        }
        return b.build()
    }

    override fun onDestroy() {
        destroying = true
        handler.removeCallbacksAndMessages(null)
        alarms.close()
        alarmIntents.values.forEach { it.second.cancel() }
        alarmIntents.clear()
        session = null; countdownToken = null
        wake?.let { if (it.isHeld) it.release() }; wake = null
        state = state.copy(running = false, classic = state.classic.copy(running = false), alarms = emptyMap())
        listener?.invoke(state)
        super.onDestroy()
    }
}

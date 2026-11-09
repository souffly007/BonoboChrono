// SPDX-License-Identifier: GPL-3.0-or-later
package fr.bonobo.chrono

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log

/** Service-owned alarms: no Activity references, one cancellable callback, one audible alert.
 * Android exposes one vibrator per app; mix both patterns before driving that vibrator.
 */
class AlarmController(context: Context, private val changed: () -> Unit) {
    companion object {
        const val FINAL_ALARM_TIMEOUT_MS = 120_000L
    }

    private data class Alarm(
        val token: String,
        val startedAt: Long,
        val durationMs: Long,
        val beepMs: Int,
        val gapMs: Int,
        var tone: ToneGenerator?,
        var player: MediaPlayer? = null,
        var lastBeep: Int = -1
    )

    private val handler = Handler(Looper.getMainLooper())
    @Suppress("DEPRECATION")
    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    private val alarms = mutableMapOf<String, Alarm>()
    private val sound = AlarmSound(context)
    val active: Map<String, String> get() = alarms.mapValues { it.value.token }
    private val step = Runnable { update() }

    private fun safely(action: () -> Unit) {
        try { action() } catch (e: RuntimeException) {
            Log.w("BonoboAlarm", "Audio/vibration unavailable", e)
        }
    }

    fun start(key: String, token: String, count: Int, beepMs: Int, gapMs: Int) {
        startTimed(key, token, (beepMs + gapMs).toLong() * count, beepMs, gapMs)
    }

    /** Final alarms repeat until explicitly stopped. */
    fun startFinal(key: String, token: String, beepMs: Int, gapMs: Int) {
        startTimed(key, token, Long.MAX_VALUE, beepMs, gapMs)
    }

    data class Snapshot(val token: String, val endsAt: Long, val beepMs: Int, val gapMs: Int)
    fun snapshots(): Map<String, Snapshot> = alarms.mapValues { (_, a) ->
        Snapshot(a.token, if (a.durationMs == Long.MAX_VALUE) Long.MAX_VALUE else a.startedAt + a.durationMs, a.beepMs, a.gapMs)
    }
    fun restore(key: String, token: String, endsAt: Long, beepMs: Int, gapMs: Int) {
        val left = if (endsAt == Long.MAX_VALUE) Long.MAX_VALUE else endsAt - SystemClock.elapsedRealtime()
        if (left > 0) startTimed(key, token, left, beepMs, gapMs)
    }

    private fun startTimed(key: String, token: String, durationMs: Long, beepMs: Int, gapMs: Int) {
        alarms.values.forEach { safely { it.tone?.stopTone() }; safely { it.player?.stop(); it.player?.release() }; it.player = null; it.lastBeep = -1 }
        alarms.remove(key)?.let { release(it) }
        var tone: ToneGenerator? = null
        if (durationMs != Long.MAX_VALUE) safely { tone = ToneGenerator(AudioManager.STREAM_ALARM, 100) }
        alarms[key] = Alarm(token, SystemClock.elapsedRealtime(), durationMs, beepMs, gapMs, tone)
        changed()
        update()
    }

    /** A stale notification must never stop a newer alarm. */
    fun stop(key: String, token: String?) {
        val alarm = alarms[key] ?: return
        if (token != alarm.token) return
        alarms.remove(key)
        release(alarm)
        changed()
        update()
    }

    fun close() {
        handler.removeCallbacks(step)
        alarms.values.forEach { release(it) }
        alarms.clear()
        safely { vibrator?.cancel() }
    }

    private fun release(alarm: Alarm) {
        val tone = alarm.tone
        alarm.tone = null
        safely { tone?.stopTone() }
        safely { tone?.release() }
        safely { alarm.player?.stop(); alarm.player?.release() }
        alarm.player = null
    }

    private fun update() {
        handler.removeCallbacks(step)
        val now = SystemClock.elapsedRealtime()
        var next = Long.MAX_VALUE
        var vibrating = false
        var removed = false
        val audible = alarms.keys.lastOrNull()
        val iterator = alarms.iterator()
        while (iterator.hasNext()) {
            val (key, alarm) = iterator.next()
            val elapsed = now - alarm.startedAt
            val period = (alarm.beepMs + alarm.gapMs).toLong()
            val lifetimeLeft = if (alarm.durationMs == Long.MAX_VALUE) Long.MAX_VALUE else alarm.durationMs - elapsed
            if (lifetimeLeft <= 0) {
                release(alarm); iterator.remove(); removed = true
                continue
            }
            if (alarm.durationMs == Long.MAX_VALUE) {
                if (key == audible && alarm.player == null) {
                    alarm.player = sound.player(true)
                    if (alarm.player == null) {
                        if (alarm.tone == null) safely { alarm.tone = ToneGenerator(AudioManager.STREAM_ALARM, 100) }
                        safely { alarm.tone?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 900) }
                    }
                }
                vibrating = true
                next = minOf(next, 1_000L)
                continue
            }
            val index = (elapsed / period).toInt()
            val within = elapsed % period
            next = minOf(next, lifetimeLeft)
            if (within < alarm.beepMs) {
                vibrating = true
                if (key == audible && alarm.lastBeep != index) {
                    alarm.lastBeep = index
                    safely {
                        alarm.tone?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD,
                            minOf(alarm.beepMs - within, lifetimeLeft).toInt())
                    }
                }
                next = minOf(next, alarm.beepMs - within)
            } else next = minOf(next, period - within)
        }
        // Rebuild the union, including when one of two simultaneous alarms is stopped.
        safely { vibrator?.cancel() }
        if (vibrating) safely {
            if (Build.VERSION.SDK_INT >= 26)
                vibrator?.vibrate(VibrationEffect.createOneShot(next.coerceAtLeast(1),
                    VibrationEffect.DEFAULT_AMPLITUDE))
            else @Suppress("DEPRECATION") vibrator?.vibrate(next.coerceAtLeast(1))
        }
        if (removed) changed()
        if (alarms.isNotEmpty()) handler.postDelayed(step, next.coerceAtLeast(1))
    }
}

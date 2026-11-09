// SPDX-License-Identifier: GPL-3.0-or-later
package fr.bonobo.chrono

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/** An exact user-requested alarm may restart the foreground service from background. */
class TimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ChronoService.ACTION_WAKE &&
            intent.action != android.app.AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED) return
        val service = Intent(context, ChronoService::class.java).setAction(ChronoService.ACTION_WAKE)
        try {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(service)
            else context.startService(service)
        } catch (e: IllegalStateException) {
            // An inexact fallback does not grant the exact-alarm background-start exemption.
            // Keep the durable deadline; the sticky service or next Activity restores it.
            android.util.Log.w("BonoboTimer", "Background restart denied; exact alarms are needed", e)
        }
    }
}

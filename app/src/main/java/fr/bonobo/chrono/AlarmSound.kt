// SPDX-License-Identifier: GPL-3.0-or-later
package fr.bonobo.chrono

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log

class AlarmSound(private val context: Context) {
    private val prefs = context.getSharedPreferences("alarm_sound", Context.MODE_PRIVATE)
    private var preview: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val stopPreview = Runnable { stopPreview() }

    fun selectedUri(): Uri? = prefs.getString("uri", null)?.let(Uri::parse)
    fun choose(uri: Uri?) { stopPreview(); prefs.edit().putString("uri", uri?.toString()).apply() }
    fun label(): String {
        val uri = selectedUri() ?: return "Sonnerie d’alarme par défaut du téléphone"
        return try {
            RingtoneManager.getRingtone(context, uri)?.getTitle(context)
                ?: "Sonnerie d’alarme par défaut du téléphone"
        } catch (_: Exception) { "Sonnerie d’alarme par défaut du téléphone" }
    }

    private fun defaultUri(): Uri = RingtoneManager.getActualDefaultRingtoneUri(context,
        RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)

    fun player(loop: Boolean): MediaPlayer? {
        val selected = selectedUri()
        for (uri in listOfNotNull(selected, defaultUri()).distinct()) {
            val media = MediaPlayer()
            try {
                media.setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                media.setDataSource(context, uri)
                media.isLooping = loop
                media.prepare()
                media.start()
                return media
            } catch (e: Exception) {
                media.release()
                Log.w("BonoboAlarm", "Ringtone unavailable: $uri", e)
                if (uri == selected) prefs.edit().remove("uri").apply()
            }
        }
        return null
    }

    fun preview() {
        stopPreview()
        preview = player(false)
        handler.postDelayed(stopPreview, 3_000)
    }

    fun stopPreview() {
        handler.removeCallbacks(stopPreview)
        preview?.run { try { stop() } catch (_: IllegalStateException) {}; release() }
        preview = null
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package fr.bonobo.chrono

import android.content.Context
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView

/** Pinned above the scrollable UI. Final alerts only; never reacts to a phase-transition beep. */
class FinalAlarmPanel(context: Context) : ScrollView(context) {
    private val intervalResult = context.caption("")
    private var visibleKeys = emptySet<String>()
    private val interval = alarmCard("Séance terminée", ChronoService.INTERVAL_ALARM, intervalResult)
    private val countdown = alarmCard("Décompte terminé", ChronoService.COUNTDOWN_ALARM,
        context.caption("00:00:00 · Temps écoulé"))
    private val scheduled = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    init {
        isFillViewport = false
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(14), context.dp(10), context.dp(14), 0)
            addView(interval); addView(countdown); addView(scheduled)
        })
        visibility = GONE
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }

    private fun alarmCard(title: String, key: String, result: android.widget.TextView) =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = context.surface(0xFF302332.toInt(), CARD, RED, 18)
            setPadding(context.dp(14), context.dp(12), context.dp(14), context.dp(12))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = context.dp(8) }
            addView(context.heading(title, 19f).apply { setTextColor(RED) })
            addView(Button(context).apply {
                text = "ARRÊTER L’ALARME"
                contentDescription = "Arrêter l’alarme : $title"
                context.styleButton(this, RED, NIGHT)
                minHeight = context.dp(60); minimumHeight = context.dp(60)
                layoutParams = LinearLayout.LayoutParams(-1, -2)
                setOnClickListener { ChronoService.stopAlarm(context, key) }
            })
            addView(result.apply { setTextColor(INK); setPadding(0, context.dp(8), 0, 0) })
            visibility = GONE
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED)
            resources.displayMetrics.heightPixels else MeasureSpec.getSize(heightMeasureSpec)
        // Two simultaneous alerts remain reachable in landscape and with large text.
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(
            (available * 0.6f).toInt(), MeasureSpec.AT_MOST))
    }

    fun render(state: ChronoService.S) {
        val keys = buildSet {
            if (state.finished && state.alarms.containsKey(ChronoService.INTERVAL_ALARM))
                add(ChronoService.INTERVAL_ALARM)
            if (state.alarms.containsKey(ChronoService.COUNTDOWN_ALARM))
                add(ChronoService.COUNTDOWN_ALARM)
            addAll(state.alarms.keys.filter { it.startsWith("scheduled.") })
        }
        // Avoid repeat TalkBack announcements on timer ticks while another session runs.
        if (keys == visibleKeys) return
        visibleKeys = keys
        intervalResult.text = "Cycle ${state.total} / ${state.total} · Séance complète"
        interval.visibility = if (ChronoService.INTERVAL_ALARM in keys) VISIBLE else GONE
        countdown.visibility = if (ChronoService.COUNTDOWN_ALARM in keys) VISIBLE else GONE
        scheduled.removeAllViews()
        keys.filter { it.startsWith("scheduled.") }.forEach { key ->
            scheduled.addView(alarmCard("Alarme programmée", key, context.caption("" )).apply {
                visibility = VISIBLE
            })
        }
        visibility = if (keys.isEmpty()) GONE else VISIBLE
    }
}

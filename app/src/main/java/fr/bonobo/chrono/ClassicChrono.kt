// SPDX-License-Identifier: GPL-3.0-or-later
package fr.bonobo.chrono

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.content.Intent
import android.os.*
import android.view.Gravity
import android.view.View
import android.widget.*

/** Chrono du bas : décompte (h:m:s) ou chrono classique. Indépendant de l'enchaînement du haut. */
class ClassicChrono(ctx: Context) : LinearLayout(ctx) {

    private var down = true
    private var running = false
    private var done = false
    private var base = 0L
    private var startAt = 0L
    private var target = 0L
    private val handler = Handler(Looper.getMainLooper())
    private var restoring = false
    private var finalAlarmVisible = false
    private var lastControlStyle: Boolean? = null
    private val timerStatus = ctx.caption("Prêt")

    private val inH = ctx.numField(GREEN, "0", "h") { if (!restoring) { done = false; update() } }
    private val inM = ctx.numField(CYAN, "0", "m") { if (!restoring) { done = false; update() } }
    private val inS = ctx.numField(ORANGE, "0", "s") { if (!restoring) { done = false; update() } }
    private val inputs = LinearLayout(ctx)
    private val timeDisplay = DigitalTimeView(ctx, 82f)
    private val btnGo = Button(ctx)
    private val btnReset = Button(ctx)
    private val tabDown = tab("Décompte", true)
    private val tabUp = tab("Chrono classique", false)

    private val tick = object : Runnable {
        override fun run() { update(); if (running) handler.postDelayed(this, 50) }
    }

    private fun stopAlarm() = ChronoService.stopAlarm(context, ChronoService.COUNTDOWN_ALARM)

    private fun command(action: String, duration: Long = target) {
        val intent = Intent(context, ChronoService::class.java).setAction(ChronoService.ACTION_CLASSIC)
            .putExtra("command", action).putExtra("down", down).putExtra("duration", duration)
        if (action == "start" && Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
        else context.startService(intent)
    }

    fun renderAlarm(state: ChronoService.S) {
        val c = state.classic
        val modeChanged = down != c.down
        down = c.down; running = c.running; done = c.done
        if (c.down && target != c.target) {
            restoring = true
            val seconds = c.target / 1000
            inH.setText((seconds / 3600).toString())
            inM.setText(((seconds % 3600) / 60).toString())
            inS.setText((seconds % 60).toString())
            restoring = false
        }
        base = c.base; startAt = c.startAt; target = c.target
        if (modeChanged) { styleTab(tabDown, down); styleTab(tabUp, !down) }
        btnGo.text = if (running) "Pause" else if (started()) "Reprendre" else "Démarrer"
        setInputsEnabled(!started())
        update()
        handler.removeCallbacks(tick)
        if (running && isAttachedToWindow) handler.post(tick)
        finalAlarmVisible = state.alarms.containsKey(ChronoService.COUNTDOWN_ALARM)
        renderStatus()
    }

    fun saveState() = Bundle().apply {
        putBoolean("down", down); putBoolean("running", running); putBoolean("done", done)
        putLong("base", base); putLong("startAt", startAt); putLong("target", target)
        putString("hours", inH.text.toString()); putString("minutes", inM.text.toString())
        putString("seconds", inS.text.toString())
    }

    fun restoreState(saved: Bundle) {
        restoring = true
        inH.setText(saved.getString("hours", "0"))
        inM.setText(saved.getString("minutes", "0"))
        inS.setText(saved.getString("seconds", "0"))
        restoring = false
        // Active state is restored by the service, never from an Activity bundle.
        renderAlarm(ChronoService.state)
    }

    private fun now() = SystemClock.elapsedRealtime()
    private fun started() = running || base > 0

    private fun tab(t: String, isDown: Boolean) = TextView(context).apply {
        text = t; gravity = Gravity.CENTER; textSize = 15f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        minHeight = context.dp(48)
        setPadding(context.dp(6), context.dp(10), context.dp(6), context.dp(10))
        layoutParams = LayoutParams(0, -2, 1f)
        setOnClickListener { selectMode(isDown) }
    }

    private fun styleTab(tv: TextView, selected: Boolean) {
        tv.isSelected = selected
        val fill = if (selected) context.surface(TILE, CARD, CYAN, 12)
            else context.roundBg(CARD, 12)
        tv.background = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x334F7BAA), fill, context.roundBg(Color.WHITE, 12))
        tv.setTextColor(if (selected) INK else MUTED)
    }

    private fun inputMs(): Long? = durationMs(
        inH.text.toString(), inM.text.toString(), inS.text.toString())

    init {
        val c = ctx
        orientation = VERTICAL
        background = c.surface()
        elevation = c.dp(5).toFloat()
        setPadding(c.dp(14), c.dp(14), c.dp(14), c.dp(14))
        addView(c.heading("Chronomètre"))

        addView(LinearLayout(c).apply {
            orientation = HORIZONTAL
            background = c.roundBg(CARD, 14)
            setPadding(c.dp(3), c.dp(3), c.dp(3), c.dp(3))
            layoutParams = LayoutParams(-1, -2).apply { topMargin = c.dp(10) }
            addView(tabDown); addView(tabUp)
        })

        addView(LinearLayout(c).apply {
            orientation = VERTICAL; gravity = Gravity.CENTER
            background = c.surface(NIGHT, 0xFF10213A.toInt(), BORDER, 18)
            layoutParams = LayoutParams(-1, -2).apply { topMargin = c.dp(12) }
            setPadding(c.dp(10), c.dp(12), c.dp(10), c.dp(12))
            addView(timeDisplay.apply {
                minHeight = c.dp(100)
                layoutParams = LayoutParams(-1, -2)
                setShadowLayer(c.dp(3).toFloat(), 0f, 0f, 0x225AE1FF)
            })
            addView(timerStatus.apply { gravity = Gravity.CENTER })
        })

        btnGo.text = "Démarrer"; btnGo.setOnClickListener { go() }
        btnReset.text = "Réinitialiser"; btnReset.setOnClickListener { reset() }
        c.styleButton(btnGo, GREEN, NIGHT)
        c.styleButton(btnReset, TILE, INK)
        btnGo.layoutParams = LayoutParams(0, -2, 1f).apply { rightMargin = c.dp(5) }
        btnReset.layoutParams = LayoutParams(0, -2, 1f).apply { leftMargin = c.dp(5) }
        addView(LinearLayout(c).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(-1, -2).apply { topMargin = c.dp(12) }
            addView(btnGo); addView(btnReset)
        })

        inputs.orientation = HORIZONTAL
        inputs.layoutParams = LayoutParams(-1, -2).apply { topMargin = c.dp(12) }
        inputs.addView(c.labelledField(inH, "heures", "Décompte, heures"))
        inputs.addView(c.labelledField(inM, "minutes", "Décompte, minutes"))
        inputs.addView(c.labelledField(inS, "secondes", "Décompte, secondes"))
        addView(inputs)

        // Constructing a new view (rotation) must not silence the service's current alarm.
        styleTab(tabDown, true); styleTab(tabUp, false)
        update()
    }

    private fun selectMode(isDown: Boolean) {
        down = isDown
        styleTab(tabDown, isDown); styleTab(tabUp, !isDown)
        inputs.visibility = if (isDown) View.VISIBLE else View.GONE
        reset(clearDuration = false)
    }

    private fun go() {
        stopAlarm()
        if (running) { command("pause"); return }
        val duration = if (down && (!started() || done)) inputMs() else target
        if (down && (duration == null || duration <= 0)) {
            Toast.makeText(context, "Durée > 0 et ≤ 365 jours", Toast.LENGTH_SHORT).show(); return
        }
        (context as? MainActivity)?.checkTimerPermissions()
        command("start", duration ?: 0)
    }

    private fun reset(clearDuration: Boolean = true) {
        stopAlarm()
        if (clearDuration) {
            restoring = true
            inH.setText("0")
            inM.setText("0")
            inS.setText("0")
            restoring = false
        }
        command("reset")
    }

    private fun setInputsEnabled(e: Boolean) { inH.isEnabled = e; inM.isEnabled = e; inS.isEnabled = e }

    private fun update() {
        if (done) { show(0); return }
        val el = base + if (running) now() - startAt else 0
        if (down) {
            if (running && el >= target) { show(0); return }
            show(if (started()) target - el else inputMs() ?: 0L)
        } else show(el)
    }

    private fun show(ms: Long) {
        val s = if (down) ms / 1000 + if (ms % 1000 != 0L) 1 else 0 else ms / 1000
        val display = "%02d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
        if (timeDisplay.text.toString() != display) timeDisplay.text = display
        timeDisplay.contentDescription = "${if (down) "Décompte" else "Chronomètre"}, " +
            "${s / 3600} heures, ${(s % 3600) / 60} minutes, ${s % 60} secondes"
        renderStatus()
    }

    /** Visual projection of the existing state; no timing or restoration decisions here. */
    private fun renderStatus() {
        val status = when {
            down && finalAlarmVisible -> "Terminé · Alarme active"
            done -> "Terminé"
            running -> if (down) "Décompte en cours" else "Chronomètre en cours"
            started() -> "En pause"
            else -> "Prêt"
        }
        if (timerStatus.text.toString() != status) timerStatus.text = status
        timerStatus.setTextColor(if (done || finalAlarmVisible) GREEN else MUTED)
        inputs.visibility = if (down && !started()) View.VISIBLE else View.GONE
        if (lastControlStyle != running) {
            lastControlStyle = running
            context.styleButton(btnGo, if (running) TILE else GREEN, if (running) CYAN else NIGHT)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        renderAlarm(ChronoService.state)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(tick)
        super.onDetachedFromWindow()
    }
}

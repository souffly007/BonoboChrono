// SPDX-License-Identifier: GPL-3.0-or-later
package fr.bonobo.chrono

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.Gravity
import android.view.WindowManager
import android.view.WindowInsetsController
import android.widget.*
import android.media.RingtoneManager
import android.net.Uri
import android.content.res.ColorStateList
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils

class MainActivity : Activity() {
    private lateinit var in1: Array<EditText>
    private lateinit var in2: Array<EditText>
    private lateinit var inRep: EditText
    private lateinit var tv1: TextView
    private lateinit var tv2: TextView
    private lateinit var tvRep: TextView
    private lateinit var tvTotal: TextView
    private lateinit var phase1: LinearLayout
    private lateinit var phase2: LinearLayout
    private lateinit var status1: TextView
    private lateinit var status2: TextView
    private lateinit var configuration: LinearLayout
    private lateinit var btn: Button
    private lateinit var btnStop: Button
    private lateinit var btnAlarm: Button
    private lateinit var classic: ClassicChrono
    private lateinit var finalAlarm: FinalAlarmPanel
    private lateinit var clockAlarms: ClockAlarmsView
    private lateinit var intervalCard: LinearLayout
    private lateinit var pages: LinearLayout
    private val navButtons = mutableListOf<LinearLayout>()
    private var selectedPage = 0
    private var lastRunning: Boolean? = null
    private var lastPhaseStyle = ""
    private var lastAlarmKeys = emptySet<String>()

    private fun row(name: String, color: Int, vararg fields: EditText) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
        addView(heading(name, 14f).apply {
            setTextColor(color)
            layoutParams = LinearLayout.LayoutParams(dp(72), -2)
        })
        val units = arrayOf("h", "min", "s")
        val descriptions = arrayOf("heures", "minutes", "secondes")
        fields.forEachIndexed { i, field ->
            addView(labelledField(field, units[i], "$name, ${descriptions[i]}"))
        }
    }

    private fun tile(name: String, color: Int, time: TextView, status: TextView, left: Boolean) =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = surface(CARD, NIGHT, BORDER, 18)
            setPadding(dp(10), dp(12), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply {
                if (left) rightMargin = dp(5) else leftMargin = dp(5)
            }
            addView(caption(name).apply { setTextColor(color); gravity = Gravity.CENTER })
            time.layoutParams = LinearLayout.LayoutParams(-1, -2)
            time.minHeight = dp(64)
            addView(time)
            addView(status.apply { gravity = Gravity.CENTER })
        }

    @Suppress("DEPRECATION")
    private fun styleWindow() {
        val decor = window.peekDecorView() ?: return
        window.statusBarColor = NIGHT
        window.navigationBarColor = NIGHT
        if (Build.VERSION.SDK_INT >= 30) {
            decor.windowInsetsController?.setSystemBarsAppearance(0,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
        } else {
            if (Build.VERSION.SDK_INT >= 23) decor.systemUiVisibility =
                decor.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            if (Build.VERSION.SDK_INT >= 26) decor.systemUiVisibility =
                decor.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        in1 = arrayOf(numField(GREEN, "0", "h"), numField(GREEN, "0", "min"), numField(GREEN, "0", "s"))
        in2 = arrayOf(numField(CYAN, "0", "h"), numField(CYAN, "0", "min"), numField(CYAN, "0", "s"))
        inRep = numField(ORANGE, "0", "cycles")
        tv1 = DigitalTimeView(this, 46f)
        tv2 = DigitalTimeView(this, 46f)
        status1 = caption("PRÊTE"); status2 = caption("PRÊTE")
        tvRep = heading("", 15f).apply { setTextColor(ORANGE); gravity = Gravity.CENTER }
        tvTotal = caption("").apply { gravity = Gravity.CENTER }
        btn = Button(this).apply {
            text = "Démarrer"
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
            setOnClickListener { toggle() }
        }
        btnStop = Button(this).apply {
            text = "Arrêter la session"
            styleButton(this, TILE, RED)
            visibility = View.GONE
            setOnClickListener {
                startService(Intent(this@MainActivity, ChronoService::class.java)
                    .setAction(ChronoService.ACTION_STOP))
            }
        }
        // Keep the existing optional stop control for short transition beeps.
        // Final alerts use the pinned priority panel instead.
        btnAlarm = Button(this).apply {
            text = "Arrêter le bip"
            styleButton(this, TILE, INK)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
            setOnClickListener { ChronoService.stopAlarm(this@MainActivity, ChronoService.INTERVAL_ALARM) }
        }
        configuration = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(row("Phase 1", GREEN, *in1))
            addView(row("Phase 2", CYAN, *in2))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
                addView(heading("Cycles", 14f).apply {
                    setTextColor(ORANGE); layoutParams = LinearLayout.LayoutParams(dp(72), -2)
                })
                addView(labelledField(inRep, "répétitions", "Nombre de cycles"))
            })
        }
        val card1 = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = surface()
            elevation = dp(5).toFloat()
            setPadding(dp(14), dp(14), dp(14), dp(14))
            addView(heading("Enchaînement"))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
                phase1 = tile("PHASE 1", GREEN, tv1, status1, true)
                phase2 = tile("PHASE 2", CYAN, tv2, status2, false)
                addView(phase1); addView(phase2)
            })
            addView(tvRep.apply { setPadding(0, dp(10), 0, dp(3)) })
            addView(tvTotal)
            addView(btn)
            addView(btnStop)
            addView(btnAlarm)
            addView(configuration)
        }
        classic = ClassicChrono(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }
        intervalCard = card1
        clockAlarms = ClockAlarmsView(this)

        savedInstanceState?.getBundle("classic")?.let { classic.restoreState(it) }
        savedInstanceState?.getStringArray("intervalInputs")?.let { values ->
            (in1 + in2 + inRep).forEachIndexed { index, field -> field.setText(values[index]) }
        }
        selectedPage = savedInstanceState?.getInt("page", 0) ?: 0

        pages = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val labels = listOf("Horloge", "Enchaînement", "Chrono")
            val icons = listOf(R.drawable.ic_nav_clock, R.drawable.ic_nav_repeat,
                R.drawable.ic_nav_stopwatch)
            labels.forEachIndexed { index, title ->
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    isClickable = true
                    isFocusable = true
                    contentDescription = when (index) {
                        0 -> "Horloge & alarmes"
                        1 -> "Enchaînement"
                        else -> "Chronomètre"
                    }
                    addView(ImageView(this@MainActivity).apply {
                        setImageResource(icons[index])
                        layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    })
                    addView(TextView(this@MainActivity).apply {
                        text = title
                        textSize = 12f
                        gravity = Gravity.CENTER
                        isSingleLine = true
                        ellipsize = TextUtils.TruncateAt.END
                        setPadding(dp(2), dp(2), dp(2), 0)
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    })
                    layoutParams = LinearLayout.LayoutParams(0, dp(60), 1f).apply {
                        if (index < labels.lastIndex) rightMargin = dp(8)
                    }
                    setOnClickListener { selectPage(index) }
                    navButtons.add(this)
                })
            }
        }

        val col = ChronoColumn(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(20))
            layoutParams = FrameLayout.LayoutParams(-1, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(2), 0, 0, dp(18))
                addView(heading("B", 25f).apply {
                    gravity = Gravity.CENTER; setTextColor(GREEN)
                    background = surface(TILE, CARD, BORDER, 14)
                    layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(12) }
                })
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(heading("BonoboChrono", 24f))
                    addView(caption("CHRONOMÈTRES & INTERVALLES"))
                })
            })
            addView(nav)
            addView(pages.apply { setPadding(0, dp(14), 0, 0) })
        }
        finalAlarm = FinalAlarmPanel(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = surface(NIGHT, 0xFF101A2E.toInt(), NIGHT, 0)
            addView(finalAlarm)
            addView(ScrollView(this@MainActivity).apply {
                isFillViewport = true; clipToPadding = false
                layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
                addView(FrameLayout(this@MainActivity).apply { addView(col) })
            })
        }
        setContentView(root)
        selectPage(selectedPage)
        // Apply system bar styling after the content is installed and the view is attached.
        // Some OEM windows dereference an uninitialised DecorView in getInsetsController().
        root.post { styleWindow() }
    }

    override fun onStart() {
        super.onStart()
        ChronoService.listener = { render(it) }
        render(ChronoService.state)
        clockAlarms.refresh()
        startService(Intent(this, ChronoService::class.java))
    }

    override fun onStop() {
        ChronoService.listener = null
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("page", selectedPage)
        outState.putBundle("classic", classic.saveState())
        outState.putStringArray("intervalInputs", (in1 + in2 + inRep).map { it.text.toString() }.toTypedArray())
        super.onSaveInstanceState(outState)
    }

    private fun selectPage(index: Int) {
        selectedPage = index
        navButtons.forEachIndexed { i, button ->
            val active = i == index
            button.background = RippleDrawable(ColorStateList.valueOf(0x334F7BAA),
                if (active) surface(GREEN, GREEN, GREEN, 16)
                else surface(TILE, CARD, BORDER, 16), roundBg(INK, 16))
            (button.getChildAt(0) as ImageView).imageTintList =
                ColorStateList.valueOf(if (active) NIGHT else INK)
            (button.getChildAt(1) as TextView).setTextColor(if (active) NIGHT else INK)
            button.isSelected = active
        }
        pages.removeAllViews()
        val view = when (index) { 0 -> clockAlarms; 1 -> intervalCard; else -> classic }
        (view.parent as? android.view.ViewGroup)?.removeView(view)
        pages.addView(view)
    }

    @Suppress("DEPRECATION")
    fun chooseAlarmSound() {
        val sound = AlarmSound(this)
        val picker = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, sound.selectedUri())
        startActivityForResult(picker, 10)
    }

    @Deprecated("Native ringtone picker result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 10 && resultCode == RESULT_OK) {
            @Suppress("DEPRECATION")
            val uri = data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            if (uri != null) try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
                // System ringtone providers usually grant access without a persisted grant.
            }
            AlarmSound(this).choose(uri)
            clockAlarms.refresh()
        }
    }

    private fun ms(f: Array<EditText>): Long? = durationMs(
        f[0].text.toString(), f[1].text.toString(), f[2].text.toString())

    private fun render(s: ChronoService.S) {
        if (s.alarms.keys != lastAlarmKeys) {
            lastAlarmKeys = s.alarms.keys
            clockAlarms.refresh()
        }
        finalAlarm.render(s)
        btnAlarm.visibility = if (s.running && s.alarms.containsKey(ChronoService.INTERVAL_ALARM))
            View.VISIBLE else View.GONE
        classic.renderAlarm(s)
        if (lastRunning != s.running) {
            lastRunning = s.running
            styleButton(btn, if (s.running) TILE else GREEN, if (s.running) CYAN else NIGHT)
        }
        btn.text = if (s.running) "Pause" else if (s.paused) "Reprendre" else "Démarrer"
        btnStop.visibility = if (s.running || s.paused) View.VISIBLE else View.GONE
        (in1 + in2 + inRep).forEach { it.isEnabled = !s.running && !s.paused }
        // Disabled configuration is replaced by the running session's compact summary.
        configuration.visibility = if (s.running || s.paused) View.GONE else View.VISIBLE
        if (s.running || s.paused) {
            tv1.text = fmtTime(if (s.phase == 1) s.remaining else s.d1)
            tv2.text = fmtTime(if (s.phase == 2) s.remaining else s.d2)
            tvRep.text = "Cycle ${s.cycle} / ${s.total}"
            val totalRemaining = (s.total - s.cycle).toLong() * (s.d1 + s.d2) +
                s.remaining + if (s.phase == 1) s.d2 else 0L
            tvTotal.text = "Temps total restant · ${fmtTime(totalRemaining)}"
        } else {
            tv1.text = fmtTime(ms(in1) ?: 0L); tv2.text = fmtTime(ms(in2) ?: 0L)
            tvRep.text = if (s.finished) "Terminé · Cycle ${s.total} / ${s.total}" else "Deux phases par cycle"
            tvTotal.text = if (s.finished) "Séance complète" else "Réglez les durées et le nombre de cycles"
        }
        tv1.contentDescription = "Phase 1, ${tv1.text}"
        tv2.contentDescription = "Phase 2, ${tv2.text}"
        val phaseStyle = "${s.running}:${s.paused}:${s.phase}:${s.finished}"
        if (lastPhaseStyle != phaseStyle) {
            lastPhaseStyle = phaseStyle
            stylePhase(phase1, tv1, status1, GREEN, s.running && s.phase == 1,
                if (s.running && s.phase == 2) "EFFECTUÉE" else "PRÊTE", s.finished)
            stylePhase(phase2, tv2, status2, CYAN, s.running && s.phase == 2,
                if (s.running && s.phase == 1) "À SUIVRE" else "PRÊTE", s.finished)
        }
    }

    private fun stylePhase(card: LinearLayout, time: TextView, status: TextView, color: Int,
                           active: Boolean, inactiveLabel: String, finished: Boolean) {
        card.background = surface(if (active) TILE else CARD, NIGHT, if (active) color else BORDER, 18)
        card.elevation = if (active) dp(3).toFloat() else 0f
        time.setTextColor(if (active) INK else MUTED)
        time.setShadowLayer(if (active) dp(4).toFloat() else 0f, 0f, 0f, 0x334FE1F4)
        status.text = if (active) "EN COURS" else if (finished) "TERMINÉE" else inactiveLabel
        status.setTextColor(if (active) color else MUTED)
    }

    private fun toggle() {
        if (ChronoService.state.running) {
            startService(Intent(this, ChronoService::class.java).setAction(ChronoService.ACTION_PAUSE))
            return
        }
        if (ChronoService.state.paused) {
            startService(Intent(this, ChronoService::class.java).setAction(ChronoService.ACTION_RESUME))
            return
        }
        val d1 = ms(in1); val d2 = ms(in2)
        val total = inRep.text.toString().toIntOrNull() ?: 0
        if (d1 == null || d2 == null || !validSession(d1, d2, total)) {
            Toast.makeText(this, "Durées et répétitions > 0 ; séance ≤ 365 jours", Toast.LENGTH_SHORT).show()
            return
        }
        checkTimerPermissions()

        val i = Intent(this, ChronoService::class.java)
            .setAction(ChronoService.ACTION_START)
            .putExtra(ChronoService.EXTRA_D1, d1)
            .putExtra(ChronoService.EXTRA_D2, d2)
            .putExtra(ChronoService.EXTRA_TOTAL, total)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
    }
    fun checkTimerPermissions() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        } else if (Build.VERSION.SDK_INT >= 24 && !(getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager)
                .areNotificationsEnabled()) notificationWarning()
        if (Build.VERSION.SDK_INT >= 31 &&
            !(getSystemService(ALARM_SERVICE) as android.app.AlarmManager).canScheduleExactAlarms()) {
            android.app.AlertDialog.Builder(this).setTitle("Alarmes précises nécessaires")
                .setMessage("Autorisez les alarmes et rappels pour déclencher le minuteur à l’heure, même écran éteint. Sans cette autorisation, l’alarme peut être retardée en veille.")
                .setPositiveButton("Ouvrir les réglages") { _, _ ->
                    startActivity(Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        android.net.Uri.parse("package:$packageName")))
                }.setNegativeButton("Plus tard", null).show()
        }
    }

    private fun notificationWarning() {
        android.app.AlertDialog.Builder(this).setTitle("Notifications désactivées")
            .setMessage("Le minuteur continue, mais sa progression et le bouton Arrêter l’alarme ne peuvent pas être affichés dans les notifications. Activez les notifications dans les réglages.")
            .setPositiveButton("Réglages") { _, _ ->
                startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName))
            }.setNegativeButton("Fermer", null).show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && (grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED))
            notificationWarning()
    }

}

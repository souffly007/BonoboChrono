// SPDX-License-Identifier: GPL-3.0-or-later
package fr.bonobo.chrono

import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.res.ColorStateList
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.widget.*
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

class ClockAlarmsView(private val activity: MainActivity) : LinearLayout(activity) {
    private val store = ScheduledAlarmStore(activity)
    private val sound = AlarmSound(activity)
    private val clock = activity.heading("", 44f).apply { gravity = Gravity.CENTER }
    private val date = activity.caption("").apply { gravity = Gravity.CENTER }
    private val list = LinearLayout(activity).apply { orientation = VERTICAL }
    private val soundName = activity.caption("").apply {
        textSize = 14f
        setTextColor(INK)
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
        layoutParams = LayoutParams(0, -2, 1f)
    }
    private val soundChoice = LinearLayout(activity).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        isFocusable = true
        background = RippleDrawable(ColorStateList.valueOf(0x334F7BAA),
            activity.surface(CARD, NIGHT, BORDER, 12), activity.roundBg(INK, 12))
        setPadding(activity.dp(12), 0, activity.dp(10), 0)
        setOnClickListener { activity.chooseAlarmSound() }
    }
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            val now = Date()
            clock.text = DateFormat.getTimeInstance(DateFormat.SHORT).format(now)
            date.text = DateFormat.getDateInstance(DateFormat.FULL).format(now)
            handler.postDelayed(this, 1000L)
        }
    }
    private val dayLabels = arrayOf("Dim", "Lun", "Mar", "Mer", "Jeu", "Ven", "Sam")

    init {
        orientation = VERTICAL
        background = activity.surface()
        setPadding(activity.dp(14), activity.dp(14), activity.dp(14), activity.dp(14))
        addView(activity.heading("Horloge & alarmes"))
        addView(clock.apply {
            layoutParams = LayoutParams(-1, -2).apply { topMargin = activity.dp(12) }
        })
        addView(date.apply {
            layoutParams = LayoutParams(-1, -2).apply { bottomMargin = activity.dp(12) }
        })

        val alarmsBlock = section()
        alarmsBlock.addView(activity.heading("Alarmes programmées", 17f))
        alarmsBlock.addView(list.apply {
            layoutParams = LayoutParams(-1, -2).apply {
                topMargin = activity.dp(10)
                bottomMargin = activity.dp(10)
            }
        })
        alarmsBlock.addView(Button(activity).apply {
            text = "Ajouter une alarme"
            activity.styleButton(this, GREEN, NIGHT)
            setOnClickListener { edit(null) }
        })
        addView(alarmsBlock)

        val soundBlock = section()
        soundBlock.addView(activity.heading("Son des alarmes", 17f))
        soundChoice.addView(icon(R.drawable.ic_alarm_sound, MUTED).apply {
            layoutParams = LayoutParams(activity.dp(20), activity.dp(20)).apply {
                rightMargin = activity.dp(10)
            }
        })
        soundChoice.addView(soundName)
        soundChoice.addView(icon(R.drawable.ic_alarm_chevron, MUTED).apply {
            layoutParams = LayoutParams(activity.dp(18), activity.dp(18)).apply {
                leftMargin = activity.dp(8)
            }
        })
        soundBlock.addView(LinearLayout(activity).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(-1, -2).apply { topMargin = activity.dp(12) }
            addView(soundChoice.apply {
                layoutParams = LayoutParams(0, activity.dp(56), 1f)
            })
            addView(ImageButton(activity).apply {
                setImageResource(R.drawable.ic_alarm_play)
                imageTintList = ColorStateList.valueOf(GREEN)
                contentDescription = "Écouter un aperçu de la sonnerie"
                background = RippleDrawable(ColorStateList.valueOf(0x334F7BAA),
                    activity.surface(TILE, CARD, BORDER, 12), activity.roundBg(INK, 12))
                layoutParams = LayoutParams(activity.dp(52), activity.dp(52)).apply {
                    leftMargin = activity.dp(8)
                }
                setOnClickListener { sound.preview() }
            })
        })
        addView(soundBlock)
        refresh()
    }

    private fun section() = LinearLayout(activity).apply {
        orientation = VERTICAL
        background = activity.surface(TILE, CARD, BORDER, 16)
        setPadding(activity.dp(12), activity.dp(14), activity.dp(12), activity.dp(14))
        layoutParams = LayoutParams(-1, -2).apply { topMargin = activity.dp(14) }
    }

    private fun icon(resource: Int, color: Int) = ImageView(activity).apply {
        setImageResource(resource)
        imageTintList = ColorStateList.valueOf(color)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); tick.run() }
    override fun onDetachedFromWindow() { handler.removeCallbacks(tick); sound.stopPreview(); super.onDetachedFromWindow() }
    fun refresh() {
        list.removeAllViews()
        soundName.text = sound.label()
        soundChoice.contentDescription = "Choisir une sonnerie, ${soundName.text}"
        val items = store.all().sortedWith(compareBy({ it.hour }, { it.minute }))
        if (items.isEmpty()) list.addView(activity.caption("Aucune alarme programmée"))
        items.forEach { alarm ->
            val card = LinearLayout(activity).apply {
                orientation = VERTICAL
                background = activity.surface(TILE, CARD, BORDER, 14)
                setPadding(activity.dp(10), activity.dp(8), activity.dp(10), activity.dp(8))
                layoutParams = LayoutParams(-1, -2).apply { topMargin = activity.dp(8) }
            }
            card.addView(LinearLayout(activity).apply {
                orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(activity.heading("%02d:%02d".format(alarm.hour, alarm.minute), 26f).apply {
                    layoutParams = LayoutParams(0, -2, 1f)
                })
                addView(Switch(activity).apply {
                    isChecked = alarm.enabled
                    contentDescription = "Activer l’alarme %02d:%02d".format(alarm.hour, alarm.minute)
                    setOnCheckedChangeListener { _, checked -> store.save(alarm.copy(enabled = checked)); refresh() }
                })
            })
            card.addView(activity.caption(if (alarm.days.isEmpty()) "Une fois" else
                alarm.days.sorted().joinToString(" · ") { dayLabels[it - 1] }))
            card.addView(LinearLayout(activity).apply {
                orientation = HORIZONTAL
                addView(Button(activity).apply {
                    text = "Modifier"; activity.styleButton(this, TILE, INK)
                    layoutParams = LayoutParams(0, -2, 1f)
                    setOnClickListener { edit(alarm) }
                })
                addView(Button(activity).apply {
                    text = "Supprimer"; activity.styleButton(this, TILE, RED)
                    layoutParams = LayoutParams(0, -2, 1f)
                    setOnClickListener {
                        AlertDialog.Builder(activity).setMessage("Supprimer cette alarme ?")
                            .setPositiveButton("Supprimer") { _, _ -> store.delete(alarm.id); refresh() }
                            .setNegativeButton("Annuler", null).show()
                    }
                })
            })
            list.addView(card)
        }
    }

    private fun edit(existing: ScheduledAlarm?) {
        val now = Calendar.getInstance()
        TimePickerDialog(activity, { _, hour, minute ->
            val selected = BooleanArray(7) { existing?.days?.contains(it + 1) == true }
            AlertDialog.Builder(activity).setTitle("Répéter certains jours ?")
                .setMultiChoiceItems(dayLabels, selected) { _, index, checked -> selected[index] = checked }
                .setPositiveButton("Enregistrer") { _, _ ->
                    val days = (1..7).filter { selected[it - 1] }.toSet()
                    val id = existing?.id ?: ((store.all().maxOfOrNull { it.id } ?: 0) + 1)
                    store.save(ScheduledAlarm(id, hour, minute, days, existing?.enabled ?: true))
                    activity.checkTimerPermissions()
                    refresh()
                }.setNegativeButton("Annuler", null).show()
        }, existing?.hour ?: now.get(Calendar.HOUR_OF_DAY), existing?.minute ?: now.get(Calendar.MINUTE),
            android.text.format.DateFormat.is24HourFormat(activity)).show()
    }
}

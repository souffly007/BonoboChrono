// SPDX-License-Identifier: GPL-3.0-or-later
package fr.bonobo.chrono

import android.animation.ObjectAnimator
import android.animation.StateListAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

// The existing phase colour names are kept to avoid changing their UI connections.
val GREEN = 0xFF61E4ED.toInt()
val CYAN = 0xFF82B4FF.toInt()
val ORANGE = 0xFFBCABFF.toInt()
val RED = 0xFFFFAAA3.toInt()
val NIGHT = 0xFF060C16.toInt()
val CARD = 0xFF111E31.toInt()
val TILE = 0xFF192B43.toInt()
val INK = 0xFFF0F6FF.toInt()
val MUTED = 0xFFAFBFD3.toInt()
val BORDER = 0xFF2A405B.toInt()

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

fun Context.roundBg(color: Int, radiusDp: Int) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = radiusDp * resources.displayMetrics.density
}

fun Context.surface(top: Int = CARD, bottom: Int = NIGHT, border: Int = BORDER,
                    radius: Int = 20) = GradientDrawable(
    GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bottom)
).apply {
    cornerRadius = dp(radius).toFloat()
    setStroke(dp(1).coerceAtLeast(1), border)
}

fun Context.styleButton(b: Button, bg: Int, fg: Int) {
    val states = StateListDrawable().apply {
        addState(intArrayOf(-android.R.attr.state_enabled), surface(TILE, CARD, BORDER, 16))
        addState(intArrayOf(), surface(bg, bg, bg, 16))
    }
    b.backgroundTintList = null
    b.background = RippleDrawable(ColorStateList.valueOf(0x334F7BAA), states, roundBg(Color.WHITE, 16))
    b.setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()),
        intArrayOf(fg, MUTED)))
    b.transformationMethod = null
    b.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    b.textSize = 17f
    b.setPadding(dp(12), dp(10), dp(12), dp(10))
    b.setSingleLine(false)
    b.maxLines = Int.MAX_VALUE
    b.ellipsize = null
    b.minHeight = dp(52); b.minimumHeight = dp(52)
    b.elevation = dp(3).toFloat()
    b.stateListAnimator = StateListAnimator().apply {
        addState(intArrayOf(android.R.attr.state_pressed, android.R.attr.state_enabled),
            ObjectAnimator.ofFloat(b, "translationZ", dp(2).toFloat()).apply { duration = 100 })
        addState(intArrayOf(), ObjectAnimator.ofFloat(b, "translationZ", 0f).apply { duration = 100 })
    }
}

fun Context.caption(text: String) = TextView(this).apply {
    this.text = text
    setTextColor(MUTED)
    textSize = 12f
    letterSpacing = 0.06f
    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
}

fun Context.heading(text: String, size: Float = 19f) = TextView(this).apply {
    this.text = text; textSize = size; setTextColor(INK)
    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
}

fun Context.numField(color: Int, def: String, hint: String, onChange: (() -> Unit)? = null) =
    EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        setText(def); this.hint = hint
        setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(color, MUTED)))
        setHintTextColor(MUTED)
        gravity = Gravity.CENTER; textSize = 19f
        typeface = Typeface.MONOSPACE
        backgroundTintList = null
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), surface(TILE, NIGHT, color, 12))
            addState(intArrayOf(), surface(CARD, NIGHT, BORDER, 12))
        }
        setPadding(dp(6), dp(6), dp(6), dp(6))
        minHeight = dp(48)
        layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply {
            leftMargin = dp(3); rightMargin = dp(3)
        }
        if (onChange != null) addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { onChange() }
        })
    }

/** Units remain visible when the input contains a value. */
fun Context.labelledField(field: EditText, unit: String, description: String) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
    if (field.id == View.NO_ID) field.id = View.generateViewId()
    field.contentDescription = description
    addView(caption(unit).apply {
        gravity = Gravity.CENTER; labelFor = field.id
        setPadding(0, 0, 0, dp(4))
    })
    field.layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
        leftMargin = dp(3); rightMargin = dp(3)
    }
    addView(field)
}

/** Avoid full-width cards and stretched controls on tablets. */
class ChronoColumn(context: Context) : LinearLayout(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = minOf(MeasureSpec.getSize(widthMeasureSpec), context.dp(840))
        super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), heightMeasureSpec)
    }
}

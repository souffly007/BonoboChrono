// SPDX-License-Identifier: GPL-3.0-or-later
package fr.bonobo.chrono

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView

/** Native tabular digits fitted to the actual content width, including long hour values. */
class DigitalTimeView(context: Context, private val preferredSp: Float = 74f) : TextView(context) {
    init {
        text = "00:00:00"
        gravity = Gravity.CENTER
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        includeFontPadding = false
        setSingleLine(true)
        setTextColor(INK)
        textSize = preferredSp
        fontFeatureSettings = "tnum"
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val available = measuredWidth - compoundPaddingLeft - compoundPaddingRight
        if (available <= 0) return
        val desired = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, preferredSp, resources.displayMetrics)
        val measurePaint = android.text.TextPaint(paint).apply { textSize = desired }
        val textWidth = measurePaint.measureText(text.toString())
        val fitted = if (textWidth > available) desired * available / textWidth else desired
        if (kotlin.math.abs(textSize - fitted) > 0.25f) {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, fitted)
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }
}

package com.quantilytix.izwi.review

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Cumulative-recording meter: a track scaled to [scaleMinutes] (the
 * "keep going" ceiling, 5 hours by default) with a marked tick at
 * [baselineMinutes] (the 2-hour training baseline). The fill is teal up to
 * the baseline and gold beyond it — recording is meant to continue past the
 * baseline, not stop there.
 */
class SessionMeterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var progressMinutes: Double = 0.0
        set(value) { field = value; invalidate() }
    var baselineMinutes: Double = 120.0
        set(value) { field = value; invalidate() }
    var scaleMinutes: Double = 300.0
        set(value) { field = value; invalidate() }

    private val trackPaint = Paint().apply { color = Color.parseColor("#E5EAE8") }
    private val fillPaint = Paint().apply { color = Color.parseColor("#1F6F5C") }
    private val bonusFillPaint = Paint().apply { color = Color.parseColor("#E8A93B") }
    private val markerPaint = Paint().apply { color = Color.parseColor("#14231F"); strokeWidth = 4f }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRoundRect(0f, 0f, w, h, h / 2, h / 2, trackPaint)

        val fraction = (progressMinutes / scaleMinutes).coerceIn(0.0, 1.0).toFloat()
        val baselineFraction = (baselineMinutes / scaleMinutes).coerceIn(0.0, 1.0).toFloat()

        if (fraction > 0f) {
            val tealWidth = w * minOf(fraction, baselineFraction)
            canvas.drawRoundRect(0f, 0f, tealWidth, h, h / 2, h / 2, fillPaint)
            if (fraction > baselineFraction) {
                canvas.drawRect(w * baselineFraction, 0f, w * fraction, h, bonusFillPaint)
            }
        }

        val markerX = w * baselineFraction
        canvas.drawLine(markerX, 0f, markerX, h, markerPaint)
    }
}

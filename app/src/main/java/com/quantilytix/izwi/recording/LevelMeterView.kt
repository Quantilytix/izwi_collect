package com.quantilytix.izwi.recording

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import java.util.LinkedList

/** Minimal rolling waveform/level view: no external chart library, just a
 * bar per recent RMS sample. Good enough for "visible capture feedback" —
 * this is a capture aid, not a lab-grade audio visualization. */
class LevelMeterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val levels = LinkedList<Float>()
    private val maxBars = 60
    private val barPaint = Paint().apply { color = Color.parseColor("#1F6F5C") }
    private val bgPaint = Paint().apply { color = Color.parseColor("#EEEEEE") }

    fun pushLevel(level: Float) {
        levels.addLast(level.coerceIn(0f, 1f))
        if (levels.size > maxBars) levels.removeFirst()
        postInvalidate()
    }

    fun clear() {
        levels.clear()
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        if (levels.isEmpty()) return

        val barWidth = width.toFloat() / maxBars
        var x = width - levels.size * barWidth
        for (level in levels) {
            val barHeight = level * height
            canvas.drawRect(x, height - barHeight, x + barWidth * 0.7f, height.toFloat(), barPaint)
            x += barWidth
        }
    }
}

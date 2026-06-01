package com.ambientlight

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class LedView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 24f
        textAlign = Paint.Align.CENTER
    }

    private var colors = IntArray(AmbientService.TOTAL_LEDS) { Color.BLACK }

    fun updateColors(newColors: IntArray) {
        colors = newColors
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val W = width.toFloat()
        val H = height.toFloat()

        val leftLeds  = AmbientService.LEFT_LEDS
        val topLeds   = AmbientService.TOP_LEDS
        val rightLeds = AmbientService.RIGHT_LEDS

        val ledSize = 28f
        val gap     = 4f
        val step    = ledSize + gap
        val radius  = ledSize / 2f
        val borderT = ledSize + 16f
        val borderL = ledSize + 16f

        // LEFT (bottom → top)
        for (i in 0 until leftLeds) {
            val cx = borderL / 2f
            val cy = H - borderT - (i * step) - radius
            paint.color = colors[i]
            canvas.drawCircle(cx, cy, radius, paint)
            drawLabel(canvas, cx, cy, i)
        }

        // TOP (left → right)
        for (i in 0 until topLeds) {
            val idx = leftLeds + i
            val cx  = borderL + (i * step) + radius
            val cy  = borderT / 2f
            paint.color = colors[idx]
            canvas.drawCircle(cx, cy, radius, paint)
            drawLabel(canvas, cx, cy, idx)
        }

        // RIGHT (top → bottom)
        for (i in 0 until rightLeds) {
            val idx = leftLeds + topLeds + i
            val cx  = W - borderL / 2f
            val cy  = borderT + (i * step) + radius
            paint.color = colors[idx]
            canvas.drawCircle(cx, cy, radius, paint)
            drawLabel(canvas, cx, cy, idx)
        }

        // border outline
        paint.color = Color.argb(40, 255, 255, 255)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        canvas.drawRect(borderL, borderT, W - borderL, H - borderT, paint)
        paint.style = Paint.Style.FILL
    }

    private fun drawLabel(canvas: Canvas, cx: Float, cy: Float, idx: Int) {
        textPaint.textSize = 16f
        textPaint.color = Color.argb(180, 255, 255, 255)
        canvas.drawText("$idx", cx, cy + 5f, textPaint)
    }
}

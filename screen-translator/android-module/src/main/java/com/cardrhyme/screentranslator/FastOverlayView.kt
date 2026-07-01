package com.cardrhyme.screentranslator

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.max
import kotlin.math.min

class FastOverlayView(context: Context) : View(context) {
    private var items: List<OverlayItem> = emptyList()
    private var status = "OCR loading"
    private var hidden = false
    private var error = false
    private var dx = 0f
    private var dy = 0f
    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = max(1f, density)
        color = Color.rgb(255, 193, 7)
    }
    private val statusText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 13f * density
        isFakeBoldText = true
    }

    fun setStatus(value: String) { status = value; error = false; invalidate() }
    fun beginCapture(value: String) { hidden = true; status = value; invalidate() }
    fun endCapture(value: String) { hidden = false; status = value; invalidate() }
    fun moveBy(x: Float, y: Float) { dx += x; dy += y; postInvalidateOnAnimation() }

    fun setResult(value: String, valueItems: List<OverlayItem>) {
        status = value
        error = false
        hidden = false
        dx = 0f
        dy = 0f
        items = valueItems
        invalidate()
    }

    fun setError(value: String) { status = "ERROR • $value"; error = true; hidden = false; invalidate() }

    override fun onDraw(canvas: Canvas) {
        if (!hidden) {
            canvas.save()
            canvas.translate(dx, dy)
            items.forEach { drawItem(canvas, it) }
            canvas.restore()
        }
        drawStatus(canvas)
    }

    private fun drawItem(canvas: Canvas, item: OverlayItem) {
        val box = item.bounds
        if (item.kind == OverlayKind.HIGHLIGHT) {
            fill.color = Color.argb(25, 255, 193, 7)
            canvas.drawRect(box, fill)
            canvas.drawRect(box, stroke)
            return
        }
        val value = item.text.orEmpty()
        if (value.isEmpty()) return
        fill.color = item.backgroundColor
        canvas.drawRect(box, fill)
        text.color = item.foregroundColor
        text.textSize = fitText(value, box)
        val lines = wrap(value, box.width(), text)
        val lineHeight = text.fontSpacing
        val firstBase = box.centerY() - (lines.size - 1) * lineHeight * 0.5f - (text.ascent() + text.descent()) * 0.5f
        canvas.save()
        canvas.clipRect(box)
        lines.forEachIndexed { index, line ->
            canvas.drawText(line, box.centerX(), firstBase + index * lineHeight, text)
        }
        canvas.restore()
    }

    private fun fitText(value: String, box: RectF): Float {
        var low = 1f
        var high = max(1f, box.height())
        repeat(9) {
            val size = (low + high) * 0.5f
            text.textSize = size
            val lines = wrap(value, box.width(), text)
            if (lines.size * text.fontSpacing <= box.height()) low = size else high = size
        }
        return low
    }

    private fun wrap(value: String, width: Float, paint: Paint): List<String> {
        if (width <= 1f) return listOf(value)
        val result = mutableListOf<String>()
        var start = 0
        while (start < value.length) {
            var end = paint.breakText(value, start, value.length, true, width, null) + start
            if (end <= start) end = start + 1
            result += value.substring(start, min(end, value.length))
            start = end
        }
        return result
    }

    private fun drawStatus(canvas: Canvas) {
        fill.color = if (error) Color.rgb(185, 30, 30) else Color.rgb(20, 125, 60)
        val pad = 8f * density
        val box = RectF(8f * density, 8f * density, 24f * density + statusText.measureText(status), 40f * density)
        canvas.drawRoundRect(box, pad, pad, fill)
        canvas.drawText(status, box.left + pad, box.bottom - pad, statusText)
    }
}

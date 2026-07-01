package com.cardrhyme.screentranslator

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import java.util.LinkedHashMap
import kotlin.math.max
import kotlin.math.min

class FastOverlayView(context: Context) : View(context) {
    private data class Ready(val item: OverlayItem, val lines: List<String>, val size: Float, val step: Float, val base: Float)
    private val ready = LinkedHashMap<String, Ready>()
    private var status = "OCR loading"
    private var hidden = false
    private var error = false
    private var dx = 0f
    private var dy = 0f
    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = max(1f, d)
        color = Color.rgb(255, 193, 7)
    }
    private val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 13f * d
        isFakeBoldText = true
    }

    fun setStatus(v: String) { status = v; error = false; invalidate() }
    fun beginCapture(v: String) { hidden = true; status = v; invalidate() }
    fun endCapture(v: String) { hidden = false; status = v; invalidate() }
    fun moveBy(x: Float, y: Float) { dx += x; dy += y; postInvalidateOnAnimation() }
    fun setError(v: String) { status = "ERROR • $v"; error = true; hidden = false; invalidate() }

    fun setResult(v: String, items: List<OverlayItem>) {
        status = v
        error = false
        hidden = false
        dx = 0f
        dy = 0f
        ready.clear()
        items.forEachIndexed { index, item -> ready[key(item, index)] = prepare(item) }
        invalidate()
    }

    fun mergeTranslations(items: List<OverlayItem>) {
        items.forEachIndexed { index, item -> ready[key(item, index)] = prepare(item) }
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        if (!hidden) {
            canvas.save()
            canvas.translate(dx, dy)
            ready.values.forEach { drawReady(canvas, it) }
            canvas.restore()
        }
        fill.color = if (error) Color.rgb(185, 30, 30) else Color.rgb(20, 125, 60)
        val box = RectF(8f * d, 8f * d, 24f * d + pill.measureText(status), 40f * d)
        canvas.drawRoundRect(box, 8f * d, 8f * d, fill)
        canvas.drawText(status, box.left + 8f * d, box.bottom - 8f * d, pill)
    }

    private fun key(item: OverlayItem, fallback: Int): String = item.id.ifBlank { "anon:$fallback:${item.bounds.left.toInt()}:${item.bounds.top.toInt()}" }

    private fun prepare(item: OverlayItem): Ready {
        val value = item.text.orEmpty()
        if (item.kind != OverlayKind.TRANSLATED || value.isEmpty()) return Ready(item, emptyList(), 0f, 0f, 0f)
        var low = 1f
        var high = max(1f, item.bounds.height())
        repeat(9) {
            val size = (low + high) * 0.5f
            text.textSize = size
            val lines = wrap(value, item.bounds.width())
            if (lines.size * text.fontSpacing <= item.bounds.height()) low = size else high = size
        }
        text.textSize = low
        val lines = wrap(value, item.bounds.width())
        val step = text.fontSpacing
        val base = -(lines.size - 1) * step * 0.5f - (text.ascent() + text.descent()) * 0.5f
        return Ready(item, lines, low, step, base)
    }

    private fun drawReady(canvas: Canvas, value: Ready) {
        val item = value.item
        val box = item.bounds
        if (item.kind == OverlayKind.HIGHLIGHT) {
            fill.color = Color.argb(25, 255, 193, 7)
            canvas.drawRect(box, fill)
            canvas.drawRect(box, stroke)
            return
        }
        fill.color = item.backgroundColor
        canvas.drawRect(box, fill)
        if (value.lines.isEmpty()) return
        text.color = item.foregroundColor
        text.textSize = value.size
        canvas.save()
        canvas.clipRect(box)
        value.lines.forEachIndexed { i, line -> canvas.drawText(line, box.centerX(), box.centerY() + value.base + i * value.step, text) }
        canvas.restore()
    }

    private fun wrap(value: String, width: Float): List<String> {
        val out = mutableListOf<String>()
        var start = 0
        while (start < value.length) {
            var end = start + text.breakText(value, start, value.length, true, width, null)
            if (end <= start) end = start + 1
            out += value.substring(start, min(end, value.length))
            start = end
        }
        return out
    }
}

package com.cardrhyme.screentranslator

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import kotlin.math.max
import kotlin.math.min

enum class OverlayKind { TRANSLATED, HIGHLIGHT }

data class OverlayItem(
    val bounds: RectF,
    val text: String? = null,
    val kind: OverlayKind,
    val backgroundColor: Int = Color.rgb(24, 24, 24),
    val foregroundColor: Int = Color.WHITE,
    val id: String = "",
)

class TranslationOverlayView(context: Context) : View(context) {
    private var items: List<OverlayItem> = emptyList()
    private var statusText: String = "OCR loading"
    private var hideItemsForCapture = false
    private var statusIsError = false
    private val density = resources.displayMetrics.density
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val highlightFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(28, 255, 193, 7) }
    private val highlightStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 193, 7)
        style = Paint.Style.STROKE
        strokeWidth = max(1f, density)
    }
    private val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val statusTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 13f * density
        isFakeBoldText = true
    }

    fun setStatus(value: String) { statusText = value; statusIsError = false; invalidate() }
    fun beginFrameCapture(value: String) { hideItemsForCapture = true; statusText = value; invalidate() }
    fun endFrameCapture(value: String) { hideItemsForCapture = false; statusText = value; invalidate() }
    fun setResult(status: String, newItems: List<OverlayItem>) {
        hideItemsForCapture = false
        statusIsError = false
        statusText = status
        items = newItems
        invalidate()
    }
    fun setError(message: String) {
        hideItemsForCapture = false
        statusIsError = true
        statusText = "ERROR • $message"
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (!hideItemsForCapture) items.forEach { if (it.kind == OverlayKind.TRANSLATED) drawTranslation(canvas, it) else drawHighlight(canvas, it.bounds) }
        drawStatus(canvas)
    }

    private fun drawHighlight(canvas: Canvas, rect: RectF) {
        canvas.drawRect(rect, highlightFillPaint)
        canvas.drawRect(rect, highlightStrokePaint)
    }

    private fun drawTranslation(canvas: Canvas, item: OverlayItem) {
        val value = item.text.orEmpty()
        backgroundPaint.color = item.backgroundColor
        canvas.drawRect(item.bounds, backgroundPaint)
        if (value.isEmpty()) return
        textPaint.color = item.foregroundColor
        textPaint.textSize = max(1f, item.bounds.height() * 0.5f)
        val layout = StaticLayout.Builder.obtain(value, 0, value.length, textPaint, item.bounds.width().toInt().coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .build()
        val scale = min(1f, item.bounds.height() / layout.height.coerceAtLeast(1).toFloat())
        canvas.save()
        canvas.clipRect(item.bounds)
        canvas.translate(item.bounds.left, item.bounds.top)
        canvas.scale(scale, scale)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun drawStatus(canvas: Canvas) {
        statusPaint.color = if (statusIsError) Color.rgb(185, 30, 30) else Color.rgb(20, 125, 60)
        val box = RectF(8f * density, 8f * density, 24f * density + statusTextPaint.measureText(statusText), 40f * density)
        canvas.drawRoundRect(box, 8f * density, 8f * density, statusPaint)
        canvas.drawText(statusText, box.left + 8f * density, box.bottom - 8f * density, statusTextPaint)
    }
}

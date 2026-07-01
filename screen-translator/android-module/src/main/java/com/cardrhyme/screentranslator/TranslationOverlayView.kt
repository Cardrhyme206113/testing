package com.cardrhyme.screentranslator

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import kotlin.math.max
import kotlin.math.min

enum class OverlayKind { TRANSLATED, HIGHLIGHT }

data class OverlayItem(
    val bounds: RectF,
    val text: String? = null,
    val kind: OverlayKind,
)

class TranslationOverlayView(context: Context) : View(context) {
    private var items: List<OverlayItem> = emptyList()
    private var statusText: String = "OVERLAY OK • loading"
    private var suppressResultBoxes = false
    private var statusIsError = false
    private val density = resources.displayMetrics.density

    private val translatedBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 18, 18, 18)
        style = Paint.Style.FILL
    }
    private val highlightFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(36, 255, 193, 7)
        style = Paint.Style.FILL
    }
    private val highlightStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 255, 193, 7)
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(245, 20, 130, 65)
        style = Paint.Style.FILL
    }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val statusTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 14f * density
        isFakeBoldText = true
    }

    fun setStatus(value: String) {
        statusText = value
        statusIsError = false
        invalidate()
    }

    fun setCapturing(capturing: Boolean, status: String) {
        suppressResultBoxes = capturing
        statusText = status
        statusIsError = false
        invalidate()
    }

    fun setResult(status: String, newItems: List<OverlayItem>) {
        suppressResultBoxes = false
        statusIsError = false
        statusText = status
        items = newItems
        invalidate()
    }

    fun setError(message: String) {
        suppressResultBoxes = false
        statusIsError = true
        statusText = "ERROR • $message"
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawStatus(canvas)
        if (suppressResultBoxes) return

        for (item in items) {
            if (item.kind == OverlayKind.TRANSLATED) drawTranslatedBox(canvas, item)
            else drawHighlight(canvas, item.bounds)
        }
    }

    private fun drawHighlight(canvas: Canvas, source: RectF) {
        val rect = RectF(
            source.left.coerceIn(0f, width.toFloat()),
            source.top.coerceIn(0f, height.toFloat()),
            source.right.coerceIn(0f, width.toFloat()),
            source.bottom.coerceIn(0f, height.toFloat()),
        )
        val corner = 4f * density
        canvas.drawRoundRect(rect, corner, corner, highlightFillPaint)
        canvas.drawRoundRect(rect, corner, corner, highlightStrokePaint)
    }

    private fun drawTranslatedBox(canvas: Canvas, item: OverlayItem) {
        val value = item.text.orEmpty()
        if (value.isBlank()) return
        val padding = 6f * density
        val sourceHeight = item.bounds.height().coerceAtLeast(18f * density)
        textPaint.textSize = (sourceHeight * 0.72f).coerceIn(13f * density, 23f * density)
        val minimumWidth = max(item.bounds.width(), 96f * density)
        val availableWidth = max(80f * density, width - item.bounds.left - 6f * density)
        val layoutWidth = min(max(minimumWidth - padding * 2, 80f * density), availableWidth).toInt()
        val layout = StaticLayout.Builder.obtain(value, 0, value.length, textPaint, layoutWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setMaxLines(3)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
        val boxWidth = max(item.bounds.width(), layout.width + padding * 2)
        val boxHeight = max(item.bounds.height(), layout.height + padding * 2)
        val left = item.bounds.left.coerceIn(0f, max(0f, width - boxWidth))
        val top = item.bounds.top.coerceIn(0f, max(0f, height - boxHeight))
        val box = RectF(left, top, left + boxWidth, top + boxHeight)
        canvas.drawRoundRect(box, 6f * density, 6f * density, translatedBackgroundPaint)
        canvas.save()
        canvas.translate(left + padding, top + padding)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun drawStatus(canvas: Canvas) {
        statusPaint.color = if (statusIsError) {
            Color.argb(245, 185, 30, 30)
        } else {
            Color.argb(245, 20, 130, 65)
        }

        val horizontalPadding = 12f * density
        val verticalPadding = 8f * density
        val textWidth = statusTextPaint.measureText(statusText)
        val box = RectF(
            10f * density,
            10f * density,
            min(width - 10f * density, 10f * density + textWidth + horizontalPadding * 2),
            10f * density + statusTextPaint.textSize + verticalPadding * 2,
        )
        canvas.drawRoundRect(box, 12f * density, 12f * density, statusPaint)
        canvas.drawText(
            statusText,
            box.left + horizontalPadding,
            box.bottom - verticalPadding - statusTextPaint.fontMetrics.descent,
            statusTextPaint,
        )
    }
}

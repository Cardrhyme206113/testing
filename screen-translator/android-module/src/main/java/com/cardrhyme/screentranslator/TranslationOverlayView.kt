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
)

class TranslationOverlayView(context: Context) : View(context) {
    private var items: List<OverlayItem> = emptyList()
    private var statusText: String = "OCR loading"
    private var hideItemsForCapture = false
    private var statusIsError = false
    private val density = resources.displayMetrics.density

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val highlightFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(28, 255, 193, 7)
        style = Paint.Style.FILL
    }
    private val highlightStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 193, 7)
        style = Paint.Style.STROKE
        strokeWidth = max(1f, density)
    }
    private val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val statusTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 13f * density
        isFakeBoldText = true
    }

    fun setStatus(value: String) {
        statusText = value
        statusIsError = false
        invalidate()
    }

    fun beginFrameCapture(value: String) {
        hideItemsForCapture = true
        statusText = value
        statusIsError = false
        invalidate()
    }

    fun endFrameCapture(value: String) {
        hideItemsForCapture = false
        statusText = value
        statusIsError = false
        invalidate()
    }

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
        super.onDraw(canvas)
        if (!hideItemsForCapture) {
            for (item in items) {
                if (item.kind == OverlayKind.TRANSLATED) drawTranslatedBox(canvas, item)
                else drawHighlight(canvas, item.bounds)
            }
        }
        drawStatus(canvas)
    }

    private fun drawHighlight(canvas: Canvas, source: RectF) {
        val rect = clipped(source)
        if (rect.width() < 1f || rect.height() < 1f) return
        val corner = min(4f * density, min(rect.width(), rect.height()) * 0.2f)
        canvas.drawRoundRect(rect, corner, corner, highlightFillPaint)
        canvas.drawRoundRect(rect, corner, corner, highlightStrokePaint)
    }

    private fun drawTranslatedBox(canvas: Canvas, item: OverlayItem) {
        val value = item.text.orEmpty()
        if (value.isBlank()) return
        val rect = clipped(item.bounds)
        if (rect.width() < 1f || rect.height() < 1f) return

        backgroundPaint.color = item.backgroundColor
        canvas.drawRect(rect, backgroundPaint)

        val widthPx = rect.width().toInt().coerceAtLeast(1)
        val heightPx = rect.height().toInt().coerceAtLeast(1)
        textPaint.color = item.foregroundColor

        var low = 0.5f
        var high = max(1f, rect.height())
        var best = makeLayout(value, widthPx, low)
        repeat(11) {
            val size = (low + high) * 0.5f
            val candidate = makeLayout(value, widthPx, size)
            if (layoutFits(candidate, value.length, heightPx)) {
                low = size
                best = candidate
            } else {
                high = size
            }
        }

        val layoutWidth = best.width.toFloat().coerceAtLeast(1f)
        val layoutHeight = best.height.toFloat().coerceAtLeast(1f)
        val emergencyScale = min(1f, min(rect.width() / layoutWidth, rect.height() / layoutHeight))
        val drawnHeight = layoutHeight * emergencyScale

        canvas.save()
        canvas.clipRect(rect)
        canvas.translate(rect.left, rect.top + (rect.height() - drawnHeight) * 0.5f)
        canvas.scale(emergencyScale, emergencyScale)
        best.draw(canvas)
        canvas.restore()
    }

    private fun makeLayout(text: String, widthPx: Int, sizePx: Float): StaticLayout {
        textPaint.textSize = sizePx.coerceAtLeast(0.5f)
        return StaticLayout.Builder.obtain(text, 0, text.length, textPaint, widthPx)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .setLineSpacing(0f, 0.92f)
            .setMaxLines(100)
            .build()
    }

    private fun layoutFits(layout: StaticLayout, textLength: Int, heightPx: Int): Boolean {
        if (layout.height > heightPx || layout.lineCount <= 0) return false
        return layout.getLineEnd(layout.lineCount - 1) >= textLength
    }

    private fun clipped(source: RectF): RectF {
        return RectF(
            source.left.coerceIn(0f, width.toFloat()),
            source.top.coerceIn(0f, height.toFloat()),
            source.right.coerceIn(0f, width.toFloat()),
            source.bottom.coerceIn(0f, height.toFloat()),
        )
    }

    private fun drawStatus(canvas: Canvas) {
        statusPaint.color = if (statusIsError) {
            Color.argb(245, 185, 30, 30)
        } else {
            Color.argb(235, 20, 125, 60)
        }
        val horizontalPadding = 10f * density
        val verticalPadding = 6f * density
        val textWidth = statusTextPaint.measureText(statusText)
        val box = RectF(
            8f * density,
            8f * density,
            min(width - 8f * density, 8f * density + textWidth + horizontalPadding * 2),
            8f * density + statusTextPaint.textSize + verticalPadding * 2,
        )
        canvas.drawRoundRect(box, 10f * density, 10f * density, statusPaint)
        canvas.drawText(
            statusText,
            box.left + horizontalPadding,
            box.bottom - verticalPadding - statusTextPaint.fontMetrics.descent,
            statusTextPaint,
        )
    }
}

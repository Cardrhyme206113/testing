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

data class OverlayItem(
    val bounds: RectF,
    val text: String,
)

class TranslationOverlayView(context: Context) : View(context) {
    private var items: List<OverlayItem> = emptyList()
    private var statusText: String = "Translator starting…"
    private val density = resources.displayMetrics.density

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(225, 18, 18, 18)
        style = Paint.Style.FILL
    }
    private val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 20, 110, 55)
        style = Paint.Style.FILL
    }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
    }
    private val statusTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 13f * density
    }

    fun setStatus(value: String) {
        statusText = value
        invalidate()
    }

    fun setResult(status: String, newItems: List<OverlayItem>) {
        statusText = status
        items = newItems
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawStatus(canvas)

        val padding = 6f * density
        val gap = 4f * density
        val corner = 7f * density

        for (item in items) {
            val sourceHeight = item.bounds.height().coerceAtLeast(18f * density)
            textPaint.textSize = (sourceHeight * 0.78f).coerceIn(14f * density, 24f * density)

            val preferredWidth = max(item.bounds.width() * 1.6f, 180f * density)
            val availableWidth = max(80f * density, width - item.bounds.left - 8f * density)
            val layoutWidth = min(preferredWidth, availableWidth).toInt().coerceAtLeast(1)
            val layout = StaticLayout.Builder.obtain(
                item.text,
                0,
                item.text.length,
                textPaint,
                layoutWidth,
            )
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .setMaxLines(3)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build()

            val boxWidth = layout.width + padding * 2
            val boxHeight = layout.height + padding * 2
            val left = item.bounds.left.coerceIn(0f, max(0f, width - boxWidth))
            val above = item.bounds.top - boxHeight - gap
            val top = if (above >= 0f) above else {
                (item.bounds.bottom + gap).coerceAtMost(max(0f, height - boxHeight))
            }
            val background = RectF(left, top, left + boxWidth, top + boxHeight)

            canvas.drawRoundRect(background, corner, corner, backgroundPaint)
            canvas.save()
            canvas.translate(left + padding, top + padding)
            layout.draw(canvas)
            canvas.restore()
        }
    }

    private fun drawStatus(canvas: Canvas) {
        val horizontalPadding = 10f * density
        val verticalPadding = 7f * density
        val textWidth = statusTextPaint.measureText(statusText)
        val box = RectF(
            10f * density,
            10f * density,
            10f * density + textWidth + horizontalPadding * 2,
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

package com.cardrhyme.screentranslator

import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.max

internal data class OcrRegion(
    val text: String,
    val bounds: RectF,
    val confidence: Float,
    val backgroundColor: Int,
)

internal object VerticalTextGrouper {
    fun merge(regions: List<OcrRegion>): List<OcrRegion> {
        if (regions.size < 2) return regions

        val japanese = regions.filter { containsJapanese(it.text) }
        val untouched = regions.filterNot { containsJapanese(it.text) }.toMutableList()
        val assigned = mutableSetOf<OcrRegion>()
        val columns = mutableListOf<List<OcrRegion>>()

        for (seed in japanese.sortedByDescending { centerX(it.bounds) }) {
            if (seed in assigned) continue
            val column = mutableListOf(seed)
            assigned += seed

            var changed: Boolean
            do {
                changed = false
                for (candidate in japanese) {
                    if (candidate in assigned) continue
                    if (column.any { belongsToSameColumn(it.bounds, candidate.bounds) }) {
                        column += candidate
                        assigned += candidate
                        changed = true
                    }
                }
            } while (changed)

            columns += column.sortedBy { it.bounds.top }
        }

        val merged = mutableListOf<OcrRegion>()
        for (column in columns.sortedByDescending { members -> centerX(union(members.map { it.bounds })) }) {
            val bounds = union(column.map { it.bounds })
            val looksVertical = column.size >= 2 && bounds.height() > bounds.width() * 1.25f
            if (!looksVertical) {
                merged += column
                continue
            }

            val combinedText = column.joinToString(separator = "") { it.text.trim() }
            val weightedConfidence = column.sumOf { (it.confidence * it.text.length.coerceAtLeast(1)).toDouble() } /
                column.sumOf { it.text.length.coerceAtLeast(1) }
            val background = averageColor(column.map { it.backgroundColor })
            merged += OcrRegion(
                text = combinedText,
                bounds = bounds,
                confidence = weightedConfidence.toFloat(),
                backgroundColor = background,
            )
        }

        untouched += merged
        return untouched.sortedWith(
            compareBy<OcrRegion> { if (it.bounds.height() > it.bounds.width() * 1.25f) 0 else 1 }
                .thenByDescending { centerX(it.bounds) }
                .thenBy { it.bounds.top },
        )
    }

    private fun belongsToSameColumn(a: RectF, b: RectF): Boolean {
        val minWidth = minOf(a.width(), b.width()).coerceAtLeast(1f)
        val overlap = minOf(a.right, b.right) - maxOf(a.left, b.left)
        val overlapRatio = overlap / minWidth
        val centerDistance = abs(centerX(a) - centerX(b))
        val horizontalMatch = overlapRatio >= 0.28f || centerDistance <= max(a.width(), b.width()) * 0.72f
        if (!horizontalMatch) return false

        val gap = when {
            a.bottom < b.top -> b.top - a.bottom
            b.bottom < a.top -> a.top - b.bottom
            else -> 0f
        }
        val typicalHeight = max(a.height(), b.height()).coerceAtLeast(1f)
        return gap <= typicalHeight * 2.2f
    }

    private fun union(rectangles: List<RectF>): RectF {
        val first = rectangles.first()
        val result = RectF(first)
        for (index in 1 until rectangles.size) result.union(rectangles[index])
        return result
    }

    private fun centerX(rect: RectF): Float = (rect.left + rect.right) * 0.5f

    private fun containsJapanese(value: String): Boolean {
        return value.any { character ->
            character.code in 0x3040..0x30FF ||
                character.code in 0x3400..0x9FFF ||
                character.code in 0xF900..0xFAFF
        }
    }

    private fun averageColor(colors: List<Int>): Int {
        if (colors.isEmpty()) return 0xFF181818.toInt()
        var red = 0L
        var green = 0L
        var blue = 0L
        for (color in colors) {
            red += color shr 16 and 0xFF
            green += color shr 8 and 0xFF
            blue += color and 0xFF
        }
        val count = colors.size
        return 0xFF000000.toInt() or
            ((red / count).toInt() shl 16) or
            ((green / count).toInt() shl 8) or
            (blue / count).toInt()
    }
}

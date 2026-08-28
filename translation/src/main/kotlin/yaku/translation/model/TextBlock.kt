package yaku.translation.model

/**
 * A rectangle in the coordinate space of the *original*, unscaled page bitmap.
 *
 * Kept as a plain data class rather than [android.graphics.Rect] so the detection and
 * recognition code stays unit-testable off-device.
 */
data class BoxF(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val area: Float get() = width * height
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    /** True when this box is taller than it is wide, the usual shape of vertical Japanese text. */
    val isVertical: Boolean get() = height > width * 1.2f

    /**
     * Grows the box by [ratio] of its *shorter* side on all four edges.
     *
     * Scaling each axis by its own length pads a tall vertical column far more vertically than
     * horizontally - 0.15 on a 95x640 column adds 14px of side margin but 96px top and bottom,
     * which runs past the text and into the curved ends of the speech bubble. The renderer then
     * paints over that, erasing part of the bubble outline. A single distance keeps the margin
     * even and proportional to the lettering.
     */
    fun expand(ratio: Float, maxWidth: Float, maxHeight: Float): BoxF {
        val margin = minOf(width, height) * ratio
        return BoxF(
            left = (left - margin).coerceAtLeast(0f),
            top = (top - margin).coerceAtLeast(0f),
            right = (right + margin).coerceAtMost(maxWidth),
            bottom = (bottom + margin).coerceAtMost(maxHeight),
        )
    }

    fun union(other: BoxF) = BoxF(
        left = minOf(left, other.left),
        top = minOf(top, other.top),
        right = maxOf(right, other.right),
        bottom = maxOf(bottom, other.bottom),
    )

    fun intersects(other: BoxF, slopX: Float = 0f, slopY: Float = 0f): Boolean =
        left - slopX < other.right && other.left < right + slopX &&
            top - slopY < other.bottom && other.top < bottom + slopY
}

/**
 * One detected region of text on a page, carried through the whole pipeline.
 *
 * [sourceText] is filled in by the recognizer and [translatedText] by the translator; both are
 * null while the corresponding stage has not run (or failed for this block alone, which is not
 * fatal - the rest of the page still renders).
 */
data class TextBlock(
    val box: BoxF,
    val confidence: Float,
    val sourceText: String? = null,
    val translatedText: String? = null,
) {
    val isTranslated: Boolean get() = !translatedText.isNullOrBlank()
}

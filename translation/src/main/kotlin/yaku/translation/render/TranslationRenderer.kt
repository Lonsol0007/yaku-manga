package yaku.translation.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import yaku.translation.model.BoxF
import yaku.translation.model.PageTranslation
import kotlin.math.sqrt

/**
 * Draws a [PageTranslation] onto a copy of the page bitmap.
 *
 * Baking the text into the bitmap rather than overlaying Compose on top of the viewer means zoom,
 * pan, double-page splitting and the cropped-borders path all keep working untouched - the reader
 * never learns that the page was modified.
 *
 * The lettering follows the conventions a typesetter uses: the translation goes *inside* the
 * speech balloon rather than onto a panel laid over it, set in one size for the whole page,
 * centred, upper case, and broken into lines of similar length.
 */
class TranslationRenderer(private val renderStyle: RenderStyle = RenderStyle()) {

    fun render(page: Bitmap, translation: PageTranslation): Bitmap {
        val output = page.copy(Bitmap.Config.ARGB_8888, true) ?: return page
        val texts = translation.blocks.mapNotNull { block ->
            block.translatedText?.takeIf { it.isNotBlank() }?.let { block.box to it }
        }
        if (texts.isEmpty()) return output

        val canvas = Canvas(output)
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = renderStyle.bubbleColor
            style = Paint.Style.FILL
        }

        // Search the page before painting anything on it. Clearing the source text first seems
        // tidier - it saves filling the glyph-shaped holes in the region - but the detector's
        // box is a rectangle around lettering that the balloon curves away from, so wiping it
        // paints through the outline and lets the flood escape into the panel.
        val balloons = BalloonFinder(output, renderStyle)
        val placements = texts.mapIndexed { index, (box, text) ->
            val stamp = (index + 1).toByte()
            Placement(
                source = box,
                balloon = balloons.around(box, stamp),
                text = if (renderStyle.uppercase) text.uppercase() else text,
                insideClaimed = balloons.claimedByAnother(box, stamp),
            )
        }.filterNot {
            // A block with no balloon of its own, sitting in one another block already letters,
            // is a fragment the detector split off rather than a second line of dialogue. Left
            // alone it takes the plaque path, and that plaque is drawn from a box wide enough to
            // paint straight through the balloon outline - a stray word and a broken balloon.
            it.balloon == null && it.insideClaimed
        }

        // A balloon is cleared in full, which removes the Japanese along with it. A block with
        // no balloon has only its own box to go on.
        balloons.eraseFound(output, renderStyle.bubbleColor)
        for (placement in placements) {
            if (placement.balloon == null) {
                canvas.drawRoundRect(
                    placement.source.toRectF(),
                    renderStyle.cornerRadius,
                    renderStyle.cornerRadius,
                    fillPaint,
                )
            }
        }

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = renderStyle.textColor
            typeface = renderStyle.typeface
            textAlign = Paint.Align.CENTER
        }

        paint.textSize = sharedTextSize(placements, paint, output.width, output.height)
        for (placement in placements) {
            draw(canvas, placement, paint, fillPaint, output.width, output.height)
        }
        return output
    }

    /**
     * The largest size at which every block on the page still fits where it has to go.
     *
     * Sizing each balloon independently maximises each in isolation and produces a page where a
     * three-word retort is set twice as large as the sentence beside it - the eye reads the size
     * as emphasis the story never intended. Letterers set a page in one size for that reason.
     * The cost is that the tightest balloon governs the rest, which is what the floor is for.
     */
    private fun sharedTextSize(
        placements: List<Placement>,
        paint: Paint,
        pageWidth: Int,
        pageHeight: Int,
    ): Float {
        var low = renderStyle.minTextSize
        var high = renderStyle.maxTextSize

        repeat(FIT_ITERATIONS) {
            val mid = (low + high) / 2f
            paint.textSize = mid
            if (placements.all { primary(it, paint, pageWidth, pageHeight) != null }) low = mid else high = mid
        }
        paint.textSize = low
        return low
    }

    /**
     * Where this block's lines go at the paint's current size, or null if they will not fit.
     *
     * Inside a balloon the limit is the shape itself, tested at the corners of the text against
     * the interior that was flooded. Without one, the block falls back to a plaque and the limit
     * is that rectangle.
     */
    private fun layout(
        placement: Placement,
        paint: Paint,
        pageWidth: Int,
        pageHeight: Int,
        useBalloon: Boolean,
        strict: Boolean,
    ): Layout? {
        val balloon = placement.balloon?.takeIf { useBalloon }
        val area = balloon?.bounds ?: horizontalBox(placement.source, pageWidth.toFloat(), pageHeight.toFloat())

        // Balloons are round: a line as wide as the bounding box only fits across the middle.
        // Starting narrower costs nothing, because the size search widens the text either way.
        val measure = if (balloon != null) {
            area.width * BALLOON_MEASURE
        } else {
            area.width - renderStyle.padding * 2
        }
        if (measure <= 0f) return null

        val lines = wrap(placement.text, paint, measure)
        val metrics = paint.fontMetrics
        val lineHeight = (metrics.descent - metrics.ascent) * renderStyle.lineSpacingMultiplier
        val width = lines.maxOf { paint.measureText(it) }
        val height = lineHeight * lines.size

        val left = area.centerX - width / 2f
        val top = area.centerY - height / 2f

        if (strict) {
            if (balloon != null) {
                // Test a slightly larger rectangle than will be drawn, so the lettering keeps
                // clear of the outline instead of merely avoiding it.
                val clear = renderStyle.balloonClearance
                val fits = balloon.contains(left - clear, top - clear) &&
                    balloon.contains(left + width + clear, top - clear) &&
                    balloon.contains(left - clear, top + height + clear) &&
                    balloon.contains(left + width + clear, top + height + clear)
                if (!fits) return null
            } else if (width > measure || height > area.height - renderStyle.padding * 2) {
                return null
            }
        }

        return Layout(lines, lineHeight, area.centerX, top, width, height, plaque = balloon == null)
    }

    private fun draw(
        canvas: Canvas,
        placement: Placement,
        paint: Paint,
        fillPaint: Paint,
        pageWidth: Int,
        pageHeight: Int,
    ) {
        // At the size floor a cramped block may fit nowhere. Drawing it slightly over its plaque
        // is recoverable; dropping it deletes a line of dialogue from the page silently.
        val laid = primary(placement, paint, pageWidth, pageHeight)
            ?: layout(placement, paint, pageWidth, pageHeight, useBalloon = false, strict = true)
            ?: layout(placement, paint, pageWidth, pageHeight, useBalloon = false, strict = false)
            ?: return

        // Without a balloon there is nothing behind the text but artwork, so it needs a panel of
        // its own. Inside a balloon the interior has already been cleared, and a panel would draw
        // a box around lettering that is meant to look like it belongs there.
        if (laid.plaque) {
            val rect = RectF(
                laid.centerX - laid.width / 2f - renderStyle.padding,
                laid.top - renderStyle.padding,
                laid.centerX + laid.width / 2f + renderStyle.padding,
                laid.top + laid.height + renderStyle.padding,
            )
            canvas.drawRoundRect(rect, renderStyle.cornerRadius, renderStyle.cornerRadius, fillPaint)
        }

        var baseline = laid.top - paint.fontMetrics.ascent
        for (line in laid.lines) {
            canvas.drawText(line, laid.centerX, baseline, paint)
            baseline += laid.lineHeight
        }
    }

    /**
     * Where a block belongs: inside its balloon, or on a plaque when it has none.
     *
     * This is what the size search asks, and it deliberately offers no second chance. Letting a
     * block fall through to a plaque *while sizing* lets the page grow until the lettering no
     * longer fits the balloons - every balloon still passes, on a plaque - and the result is a
     * line set wider than the shape it is supposed to sit in. The page is sized so the balloons
     * hold their lines, which costs a step of type size and is the whole point of the exercise.
     */
    private fun primary(placement: Placement, paint: Paint, pageWidth: Int, pageHeight: Int): Layout? =
        layout(
            placement,
            paint,
            pageWidth,
            pageHeight,
            useBalloon = placement.balloon != null,
            strict = true,
        )

    /**
     * Greedy wrap, then the narrowest measure that keeps the same number of lines.
     *
     * A greedy wrap fills each line to the limit and leaves the remainder on the last one, so a
     * balloon ends with a full line above a single orphaned word. Re-wrapping as narrow as the
     * line count allows evens them out, which is what gives set dialogue its symmetrical shape.
     */
    private fun wrap(text: String, paint: Paint, measure: Float): List<String> {
        val words = text.split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return listOf(text)

        val greedy = wrapAt(words, paint, measure)
        var low = 0f
        var high = measure
        var best = greedy

        repeat(BALANCE_ITERATIONS) {
            val mid = (low + high) / 2f
            val candidate = wrapAt(words, paint, mid)
            if (candidate.size <= greedy.size && candidate.all { paint.measureText(it) <= mid }) {
                best = candidate
                high = mid
            } else {
                low = mid
            }
        }
        return best
    }

    private fun wrapAt(words: List<String>, paint: Paint, measure: Float): List<String> {
        val lines = mutableListOf<String>()
        var current = ""
        for (word in words) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (current.isEmpty() || paint.measureText(candidate) <= measure) {
                current = candidate
            } else {
                lines += current
                current = word
            }
        }
        if (current.isNotEmpty()) lines += current
        return lines
    }

    /**
     * Reshapes a vertical source box into one English can be set in.
     *
     * Only reached when no balloon was found. The detector reports where the *source* text is,
     * and Japanese is usually set vertically: a nine-character line arrives as a column about
     * 95px wide and 640px tall. Setting an English sentence in a measure that narrow breaks
     * words mid-syllable and stacks them into a ragged ribbon.
     */
    private fun horizontalBox(box: BoxF, pageWidth: Float, pageHeight: Float): BoxF {
        if (!box.isVertical) return box

        val width = sqrt(box.area * renderStyle.horizontalAspect)
            .coerceAtMost(box.width * renderStyle.maxWidthGrowth)
            .coerceAtMost(pageWidth)
        val height = (box.area / width).coerceAtMost(box.height)

        val left = (box.centerX - width / 2f).coerceIn(0f, (pageWidth - width).coerceAtLeast(0f))
        val top = (box.centerY - height / 2f).coerceIn(0f, (pageHeight - height).coerceAtLeast(0f))

        return BoxF(left, top, left + width, top + height)
    }

    private fun BoxF.toRectF() = RectF(left, top, right, bottom)

    private class Placement(
        val source: BoxF,
        val balloon: Balloon?,
        val text: String,
        val insideClaimed: Boolean,
    )

    private class Layout(
        val lines: List<String>,
        val lineHeight: Float,
        val centerX: Float,
        val top: Float,
        val width: Float,
        val height: Float,
        val plaque: Boolean,
    )

    private companion object {
        const val FIT_ITERATIONS = 9
        const val BALANCE_ITERATIONS = 12

        /** Fraction of a balloon's width a line may use; the shape narrows away from the middle. */
        const val BALLOON_MEASURE = 0.80f
    }
}

/** The interior of one speech balloon, held as a stamp in [BalloonFinder]'s ownership map. */
internal class Balloon(
    val bounds: BoxF,
    private val stamp: Byte,
    private val owner: ByteArray,
    private val width: Int,
    private val height: Int,
) {
    fun contains(x: Float, y: Float): Boolean {
        val px = x.toInt()
        val py = y.toInt()
        if (px < 0 || py < 0 || px >= width || py >= height) return false
        return owner[py * width + px] == stamp
    }
}

/**
 * Finds the balloon a block of text sits in by flooding the light area around it.
 *
 * A balloon is a pale region closed by an outline, so its interior is exactly the light pixels
 * reachable from the text without crossing that outline. Nothing here understands what a balloon
 * *is*; when the flood escapes - an open-ended caption, lettering laid straight over artwork, a
 * page too dark to threshold - it is abandoned and the caller falls back to drawing a panel.
 */
internal class BalloonFinder(bitmap: Bitmap, private val style: RenderStyle) {

    private val width = bitmap.width
    private val height = bitmap.height
    private val pixels = IntArray(width * height).also {
        bitmap.getPixels(it, 0, width, 0, 0, width, height)
    }

    /** Which balloon claimed each pixel, 0 for none. Doubles as the visited set. */
    private val owner = ByteArray(width * height)

    /** Horizontal extent of the flood on each row, used to close it over its glyphs. */
    private val rowMin = IntArray(height)
    private val rowMax = IntArray(height)

    private val maxArea = (width.toLong() * height * style.maxBalloonPageFraction).toInt()
    private val stack = IntArray(maxArea + 1)
    private val found = mutableSetOf<Byte>()

    fun around(box: BoxF, stamp: Byte): Balloon? {
        val seed = seedIn(box) ?: return null

        for (y in 0 until height) {
            rowMin[y] = Int.MAX_VALUE
            rowMax[y] = -1
        }

        var top = 0
        stack[top++] = seed
        owner[seed] = stamp

        var area = 0
        var minX = width
        var maxX = -1
        var minY = height
        var maxY = -1

        while (top > 0) {
            val index = stack[--top]
            val x = index % width
            val y = index / width

            area++
            if (area > maxArea) return abandon(stamp)

            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
            if (x < rowMin[y]) rowMin[y] = x
            if (x > rowMax[y]) rowMax[y] = x

            // A flood loose in open paper claims pixels faster than it retires them, and the
            // area check above counts only the retired ones. Measured on a blank page the stack
            // passes its allocation about a thousand pixels before that check can fire, so the
            // room for these four neighbours has to be confirmed rather than assumed. Running
            // out of it means the flood is not in a balloon any more.
            if (top + 4 > stack.size) return abandon(stamp)

            if (x > 0) top = push(index - 1, stamp, top)
            if (x < width - 1) top = push(index + 1, stamp, top)
            if (y > 0) top = push(index - width, stamp, top)
            if (y < height - 1) top = push(index + width, stamp, top)
        }

        // The flood follows the paper *around* the lettering, so every glyph is a hole in it.
        // Closing each row between its two extremes takes them in, which both cleans the whole
        // balloon and stops a corner test failing merely because it landed on a stroke.
        for (y in minY..maxY) {
            if (rowMax[y] < 0) continue
            for (x in rowMin[y]..rowMax[y]) {
                val index = y * width + x
                if (owner[index] == ZERO) owner[index] = stamp
            }
        }

        found += stamp
        return Balloon(
            bounds = BoxF(minX.toFloat(), minY.toFloat(), maxX.toFloat(), maxY.toFloat()),
            stamp = stamp,
            owner = owner,
            width = width,
            height = height,
        )
    }

    /** Repaints every interior that was found, clearing whatever the source erase missed. */
    fun eraseFound(bitmap: Bitmap, color: Int) {
        if (found.isEmpty()) return
        for (i in pixels.indices) {
            if (owner[i] != ZERO && owner[i] in found) pixels[i] = color
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    }

    /**
     * Gives up on a flood and takes its claim back.
     *
     * The stamps are written as the flood advances, so a run that is abandoned half way still
     * owns everything it reached. Left behind, [claimedByAnother] reads them as a balloon that
     * was lettered - and every later block inside that area, which after an escape can be most
     * of the page, is discarded as a duplicate. The dialogue would go missing with nothing to
     * show why.
     */
    private fun abandon(stamp: Byte): Balloon? {
        for (i in owner.indices) {
            if (owner[i] == stamp) owner[i] = ZERO
        }
        return null
    }

    private fun push(index: Int, stamp: Byte, top: Int): Int {
        if (owner[index] != ZERO || !isLight(pixels[index])) return top
        owner[index] = stamp
        stack[top] = index
        return top + 1
    }

    private fun isLight(pixel: Int): Boolean {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000 > style.lightThreshold
    }

    /** True when some other balloon already covers this box, so its text is already set. */
    fun claimedByAnother(box: BoxF, stamp: Byte): Boolean {
        val left = box.left.toInt().coerceIn(0, width - 1)
        val right = box.right.toInt().coerceIn(0, width - 1)
        val top = box.top.toInt().coerceIn(0, height - 1)
        val bottom = box.bottom.toInt().coerceIn(0, height - 1)
        for (y in top..bottom) {
            for (x in left..right) {
                val other = owner[y * width + x]
                if (other != ZERO && other != stamp) return true
            }
        }
        return false
    }

    private fun seedIn(box: BoxF): Int? {
        val left = box.left.toInt().coerceIn(0, width - 1)
        val right = box.right.toInt().coerceIn(0, width - 1)
        val top = box.top.toInt().coerceIn(0, height - 1)
        val bottom = box.bottom.toInt().coerceIn(0, height - 1)
        // Work outwards from the centre. The corners of a text box frequently fall outside the
        // balloon that encloses it - on a wide flat balloon the top-left corner lands on the
        // panel - and seeding there floods the panel instead, which is large enough to look
        // plausible and wrong enough to set the line outside the balloon entirely.
        val centerX = (left + right) / 2
        val centerY = (top + bottom) / 2
        for (dy in 0..(bottom - top) / 2 + 1) {
            for (y in intArrayOf(centerY - dy, centerY + dy)) {
                if (y < top || y > bottom) continue
                for (dx in 0..(right - left) / 2 + 1) {
                    for (x in intArrayOf(centerX - dx, centerX + dx)) {
                        if (x < left || x > right) continue
                        val index = y * width + x
                        if (owner[index] == ZERO && isLight(pixels[index])) return index
                    }
                }
            }
        }
        return null
    }

    private companion object {
        const val ZERO: Byte = 0
    }
}

data class RenderStyle(
    val bubbleColor: Int = Color.WHITE,
    val borderColor: Int = Color.argb(36, 0, 0, 0),
    val borderWidth: Float = 1.5f,
    val textColor: Int = Color.BLACK,
    /**
     * Sized for a manga page, which is 1200-2000px across - not for screen density. Only the
     * fallback plaque uses these; lettering inside a balloon needs no panel of its own.
     */
    val cornerRadius: Float = 18f,
    val padding: Float = 16f,
    /**
     * The floor for the page-wide size. Every block shares one size, so the tightest balloon
     * sets it for the rest; below this the page stops being worth reading and clipping the odd
     * cramped block is the better trade.
     */
    val minTextSize: Float = 12f,
    val maxTextSize: Float = 72f,
    val lineSpacingMultiplier: Float = 1.06f,
    /** Width-to-height ratio aimed for when re-setting a vertical column horizontally. */
    val horizontalAspect: Float = 2.5f,
    /** Ceiling on that widening, so a narrow column cannot become a banner across the page. */
    val maxWidthGrowth: Float = 3f,
    /** Comics set dialogue in capitals, and it is what the eye expects to find in a balloon. */
    val uppercase: Boolean = true,
    /** Above this luma a pixel counts as balloon paper rather than ink or artwork. */
    val lightThreshold: Int = 200,
    /** A flood larger than this escaped the balloon, and is discarded. */
    val maxBalloonPageFraction: Float = 0.25f,
    /** How far the lettering stays clear of the balloon outline, in page pixels. */
    val balloonClearance: Float = 8f,
    val typeface: android.graphics.Typeface? = null,
)

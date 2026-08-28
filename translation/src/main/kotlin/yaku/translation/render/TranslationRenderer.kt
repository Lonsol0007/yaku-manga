package yaku.translation.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import yaku.translation.model.BoxF
import yaku.translation.model.PageTranslation
import yaku.translation.model.TextBlock
import kotlin.math.sqrt

/**
 * Draws a [PageTranslation] onto a copy of the page bitmap.
 *
 * Baking the text into the bitmap rather than overlaying Compose on top of the viewer means zoom,
 * pan, double-page splitting and the cropped-borders path all keep working untouched - the reader
 * never learns that the page was modified.
 */
class TranslationRenderer(private val renderStyle: RenderStyle = RenderStyle()) {

    fun render(page: Bitmap, translation: PageTranslation): Bitmap {
        val output = page.copy(Bitmap.Config.ARGB_8888, true) ?: return page
        val canvas = Canvas(output)

        val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = renderStyle.bubbleColor
            style = Paint.Style.FILL
        }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = renderStyle.borderColor
            style = Paint.Style.STROKE
            strokeWidth = renderStyle.borderWidth
        }

        for (block in translation.blocks) {
            val text = block.translatedText ?: continue
            drawBlock(canvas, block, text, boxPaint, borderPaint, output.width, output.height)
        }
        return output
    }

    private fun drawBlock(
        canvas: Canvas,
        block: TextBlock,
        text: String,
        boxPaint: Paint,
        borderPaint: Paint,
        pageWidth: Int,
        pageHeight: Int,
    ) {
        // Erase the source text over its own footprint. Keeping this rect exactly where the
        // detector found the glyphs means that on a real page it lands inside the bubble -
        // white on white - instead of stamping a patch across the artwork around it.
        val source = block.box
        canvas.drawRoundRect(
            RectF(source.left, source.top, source.right, source.bottom),
            renderStyle.cornerRadius,
            renderStyle.cornerRadius,
            boxPaint,
        )

        val box = horizontalBox(source, pageWidth.toFloat(), pageHeight.toFloat())
        val rect = RectF(box.left, box.top, box.right, box.bottom)
        canvas.drawRoundRect(rect, renderStyle.cornerRadius, renderStyle.cornerRadius, boxPaint)
        if (renderStyle.borderWidth > 0f) {
            canvas.drawRoundRect(rect, renderStyle.cornerRadius, renderStyle.cornerRadius, borderPaint)
        }

        val innerWidth = (box.width - renderStyle.padding * 2).toInt()
        val innerHeight = box.height - renderStyle.padding * 2
        if (innerWidth <= 0 || innerHeight <= 0f) return

        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = renderStyle.textColor
            typeface = renderStyle.typeface
        }

        val layout = fitText(text, paint, innerWidth, innerHeight) ?: return

        canvas.save()
        // Centre the block vertically; horizontal centring comes from ALIGN_CENTER.
        val offsetY = box.top + renderStyle.padding + ((innerHeight - layout.height) / 2f).coerceAtLeast(0f)
        canvas.translate(box.left + renderStyle.padding, offsetY)
        layout.draw(canvas)
        canvas.restore()
    }

    /**
     * Reshapes a vertical source box into one English can be set in.
     *
     * The detector reports where the *source* text is, and Japanese is usually set vertically:
     * a nine-character line arrives as a column about 95px wide and 640px tall. Setting an
     * English sentence in a measure that narrow breaks words mid-syllable and stacks them into
     * a ragged ribbon - the text is present but unreadable, which is what "the overlay is wrong"
     * looks like in practice. Reshaping to a horizontal plaque of the same area keeps the patch
     * proportional to how much text there is, while giving the fitter a usable measure.
     *
     * Area is preserved rather than fixed, and growth is capped, so a tall thin column does not
     * balloon into a banner across the artwork.
     */
    private fun horizontalBox(box: BoxF, pageWidth: Float, pageHeight: Float): BoxF {
        if (!box.isVertical) return box

        val width = sqrt(box.area * renderStyle.horizontalAspect)
            .coerceAtMost(box.width * renderStyle.maxWidthGrowth)
            .coerceAtMost(pageWidth)
        val height = (box.area / width).coerceAtMost(box.height)

        // Keep it centred on the text it replaces, then slide it back inside the page rather
        // than clipping - a plaque half off the edge would cut the sentence in half.
        val left = (box.centerX - width / 2f).coerceIn(0f, (pageWidth - width).coerceAtLeast(0f))
        val top = (box.centerY - height / 2f).coerceIn(0f, (pageHeight - height).coerceAtLeast(0f))

        return BoxF(left, top, left + width, top + height)
    }

    /**
     * Binary-searches the largest font size whose wrapped layout still fits the bubble.
     *
     * Bubbles are sized for the source language, and English is typically wider than the Japanese
     * it replaces, so a fixed size either overflows on long lines or looks tiny on short ones.
     */
    private fun fitText(text: String, paint: TextPaint, width: Int, height: Float): StaticLayout? {
        var low = renderStyle.minTextSize
        var high = renderStyle.maxTextSize
        var best: StaticLayout? = null

        repeat(FIT_ITERATIONS) {
            val mid = (low + high) / 2f
            paint.textSize = mid
            val layout = buildLayout(text, paint, width)
            if (layout.height <= height) {
                best = layout
                low = mid
            } else {
                high = mid
            }
        }

        if (best == null) {
            // Even the smallest size overflows; use it anyway and let the text clip rather than
            // dropping the translation entirely.
            paint.textSize = renderStyle.minTextSize
            best = buildLayout(text, paint, width)
        }
        return best
    }

    private fun buildLayout(text: String, paint: TextPaint, width: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, renderStyle.lineSpacingMultiplier)
            .setIncludePad(false)
            .build()

    private companion object {
        const val FIT_ITERATIONS = 8
    }
}

data class RenderStyle(
    val bubbleColor: Int = Color.WHITE,
    val borderColor: Int = Color.argb(40, 0, 0, 0),
    val borderWidth: Float = 1.5f,
    val textColor: Int = Color.BLACK,
    val cornerRadius: Float = 6f,
    val padding: Float = 6f,
    val minTextSize: Float = 9f,
    val maxTextSize: Float = 64f,
    /** Width-to-height ratio aimed for when re-setting a vertical column horizontally. */
    val horizontalAspect: Float = 2.5f,
    /** Ceiling on that widening, so a narrow column cannot become a banner across the page. */
    val maxWidthGrowth: Float = 3f,
    val lineSpacingMultiplier: Float = 1.05f,
    val typeface: android.graphics.Typeface? = null,
)

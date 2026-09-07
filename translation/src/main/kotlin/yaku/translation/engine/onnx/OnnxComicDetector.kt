package yaku.translation.engine.onnx

import ai.onnxruntime.OnnxTensor
import android.graphics.Bitmap
import yaku.translation.engine.TextDetector
import yaku.translation.model.BoxF
import yaku.translation.model.TextBlock
import yaku.translation.store.PackConfig

/**
 * A detector trained on comics, which answers with boxes rather than a probability map.
 *
 * The segmentation detector reports where ink is and leaves everything else to be worked out:
 * which blobs belong to the same line, which are artwork rather than lettering, and where the
 * balloon around them might be. On a page of real artwork that guesswork fails - measured on
 * one scan it returned twenty-two boxes covering most of the sheet, several of them single
 * characters torn off a column.
 *
 * This model was trained on comics and returns three kinds of thing directly: the balloons,
 * the text inside them, and the text that stands on the artwork. The balloon is the valuable
 * part, because that is where a translation has to be set and it cannot be derived from the
 * text's own box.
 */
class OnnxComicDetector(
    private val model: OrtModel,
    private val config: PackConfig,
) : TextDetector {

    private class Detection(val label: Int, val score: Float, val box: BoxF)

    override fun detect(page: Bitmap): List<TextBlock> {
        val detections = infer(page)
        val bubbles = detections.filter { it.label == CLASS_BUBBLE }.map { it.box }

        return detections
            .filter { it.label == CLASS_TEXT_IN_BUBBLE || it.label == CLASS_TEXT_FREE }
            .map { detection ->
                TextBlock(
                    box = detection.box,
                    confidence = detection.score,
                    // Only text the model put inside a balloon gets one. A sound effect lying
                    // across the artwork has no balloon to be set in, and handing it the
                    // nearest one would letter it into a balloon it has nothing to do with.
                    bubble = if (detection.label == CLASS_TEXT_IN_BUBBLE) {
                        bubbles.smallestContaining(detection.box)
                    } else {
                        null
                    },
                )
            }
            .sortedWith(readingOrder())
    }

    private fun infer(page: Bitmap): List<Detection> {
        val scaled = Bitmap.createScaledBitmap(page, INPUT_SIZE, INPUT_SIZE, true)
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        scaled.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        if (scaled !== page) scaled.recycle()

        // Planar RGB scaled to 0..1. This export applies no mean or standard deviation, unlike
        // the segmentation detector, so applying the pack's would shift every channel.
        val plane = INPUT_SIZE * INPUT_SIZE
        val data = FloatArray(3 * plane)
        for (i in pixels.indices) {
            val pixel = pixels[i]
            data[i] = ((pixel shr 16) and 0xFF) / 255f
            data[plane + i] = ((pixel shr 8) and 0xFF) / 255f
            data[2 * plane + i] = (pixel and 0xFF) / 255f
        }

        val image = model.floatTensor(data, longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong()))
        // The graph maps its own boxes back onto the page, so it has to be told the page size;
        // the boxes come out in page coordinates rather than in the 640px input's.
        val target = model.longTensor(
            longArrayOf(page.width.toLong(), page.height.toLong()),
            longArrayOf(1, 2),
        )

        return image.use { images ->
            target.use { sizes ->
                model.run(mapOf(INPUT_IMAGES to images, INPUT_SIZES to sizes)) { results ->
                    val labels = results.tensor(OUTPUT_LABELS).longBuffer
                    val boxes = results.tensor(OUTPUT_BOXES).floatBuffer
                    val scores = results.tensor(OUTPUT_SCORES).floatBuffer

                    val width = page.width.toFloat()
                    val height = page.height.toFloat()
                    val found = ArrayList<Detection>()
                    for (i in 0 until scores.remaining()) {
                        val score = scores.get(i)
                        if (score < config.detectorMinConfidence) continue
                        val at = i * 4
                        found += Detection(
                            label = labels.get(i).toInt(),
                            score = score,
                            box = BoxF(
                                left = boxes.get(at).coerceIn(0f, width),
                                top = boxes.get(at + 1).coerceIn(0f, height),
                                right = boxes.get(at + 2).coerceIn(0f, width),
                                bottom = boxes.get(at + 3).coerceIn(0f, height),
                            ),
                        )
                    }
                    found
                }
            }
        }
    }

    private fun ai.onnxruntime.OrtSession.Result.tensor(name: String): OnnxTensor =
        get(name).orElseThrow { IllegalStateException("Detector has no output '$name'") } as OnnxTensor

    /**
     * The smallest balloon holding this text.
     *
     * Balloons overlap where characters talk over one another, and the enclosing one is the one
     * the line belongs to. Taking the first match would sometimes set a line into the larger
     * balloon lying behind its own.
     */
    private fun List<BoxF>.smallestContaining(box: BoxF): BoxF? =
        filter {
            it.left <= box.centerX && box.centerX <= it.right &&
                it.top <= box.centerY && box.centerY <= it.bottom
        }.minByOrNull { it.area }

    /** Matches the segmentation detector's order, so a page reads the same either way. */
    private fun readingOrder(): Comparator<TextBlock> =
        compareBy<TextBlock> { (it.box.top / ROW_BUCKET).toInt() }
            .thenByDescending { it.box.right }

    override fun close() = model.close()

    private companion object {
        const val INPUT_SIZE = 640

        const val INPUT_IMAGES = "images"
        const val INPUT_SIZES = "orig_target_sizes"
        const val OUTPUT_LABELS = "labels"
        const val OUTPUT_BOXES = "boxes"
        const val OUTPUT_SCORES = "scores"

        const val CLASS_BUBBLE = 0
        const val CLASS_TEXT_IN_BUBBLE = 1
        const val CLASS_TEXT_FREE = 2

        const val ROW_BUCKET = 64f
    }
}

package yaku.translation.engine.onnx

import android.graphics.Bitmap
import yaku.translation.model.BoxF
import yaku.translation.model.TextBlock
import yaku.translation.store.PackConfig
import java.io.Closeable
import java.util.ArrayDeque

/**
 * Segmentation-based text detector (DBNet family).
 *
 * The model emits a per-pixel text probability map at the input resolution. We binarise it,
 * label connected components, and turn each component into a box. This is the cheap half of the
 * usual DB post-process - we skip polygon fitting because the recogniser only ever gets an
 * axis-aligned crop anyway.
 */
class OnnxTextDetector(
    private val model: OrtModel,
    private val config: PackConfig,
) : Closeable {

    fun detect(page: Bitmap): List<TextBlock> {
        val size = config.detectorInputSize
        val letterboxed = ImageTensors.letterbox(page, size, config.detectorMean, config.detectorStd)

        val input = model.floatTensor(letterboxed.data, longArrayOf(1, 3, size.toLong(), size.toLong()))
        val boxes = input.use { tensor ->
            model.run(mapOf(config.detectorInputName to tensor), config.detectorOutputName) { data, shape ->
                val mapWidth = shape[shape.size - 1].toInt()
                val mapHeight = shape[shape.size - 2].toInt()
                extractBoxes(data, mapWidth, mapHeight, size)
            }
        }

        val minArea = config.detectorMinAreaRatio * page.width * page.height
        return boxes
            .map { letterboxed.unmap(it, page.width, page.height) }
            .map { it.expand(config.detectorBoxExpand, page.width.toFloat(), page.height.toFloat()) }
            .filter { it.area >= minArea && it.width > 4f && it.height > 4f }
            .let(::mergeNeighbours)
            .sortedWith(readingOrder())
            .map { TextBlock(box = it, confidence = 1f) }
    }

    /** Binarise the probability map and label 4-connected components. */
    private fun extractBoxes(prob: FloatArray, mapWidth: Int, mapHeight: Int, inputSize: Int): List<BoxF> {
        val threshold = config.detectorThreshold
        val visited = BooleanArray(mapWidth * mapHeight)
        val boxes = ArrayList<BoxF>()
        val queue = ArrayDeque<Int>()

        // The map may be emitted at a lower resolution than the input; scale boxes back up.
        val scaleX = inputSize.toFloat() / mapWidth
        val scaleY = inputSize.toFloat() / mapHeight

        for (start in 0 until mapWidth * mapHeight) {
            if (visited[start] || prob[start] < threshold) continue

            var minX = Int.MAX_VALUE
            var minY = Int.MAX_VALUE
            var maxX = Int.MIN_VALUE
            var maxY = Int.MIN_VALUE

            visited[start] = true
            queue.add(start)
            while (queue.isNotEmpty()) {
                val index = queue.poll()
                val x = index % mapWidth
                val y = index / mapWidth
                if (x < minX) minX = x
                if (y < minY) minY = y
                if (x > maxX) maxX = x
                if (y > maxY) maxY = y

                if (x > 0) push(queue, visited, prob, index - 1, threshold)
                if (x < mapWidth - 1) push(queue, visited, prob, index + 1, threshold)
                if (y > 0) push(queue, visited, prob, index - mapWidth, threshold)
                if (y < mapHeight - 1) push(queue, visited, prob, index + mapWidth, threshold)
            }

            boxes.add(
                BoxF(
                    left = minX * scaleX,
                    top = minY * scaleY,
                    right = (maxX + 1) * scaleX,
                    bottom = (maxY + 1) * scaleY,
                ),
            )
        }
        return boxes
    }

    private fun push(
        queue: ArrayDeque<Int>,
        visited: BooleanArray,
        prob: FloatArray,
        index: Int,
        threshold: Float,
    ) {
        if (visited[index] || prob[index] < threshold) return
        visited[index] = true
        queue.add(index)
    }

    /**
     * Joins boxes that are almost certainly one utterance.
     *
     * Vertical Japanese text comes out of the detector as one component per column, so a
     * three-column bubble would otherwise be recognised as three unrelated fragments and
     * translated out of order. Columns inside a bubble sit close together, so a small
     * proximity merge recovers the whole bubble.
     */
    private fun mergeNeighbours(boxes: List<BoxF>): List<BoxF> {
        if (boxes.size < 2) return boxes
        val working = boxes.toMutableList()
        var merged = true
        while (merged) {
            merged = false
            outer@ for (i in working.indices) {
                for (j in i + 1 until working.size) {
                    val a = working[i]
                    val b = working[j]
                    val slopX = minOf(a.width, b.width) * MERGE_SLOP
                    val slopY = minOf(a.height, b.height) * MERGE_SLOP
                    if (!a.intersects(b, slopX, slopY)) continue
                    // Only merge similarly-shaped neighbours; a caption strip should not swallow
                    // a nearby sound effect.
                    val ratio = maxOf(a.height, b.height) / minOf(a.height, b.height).coerceAtLeast(1f)
                    if (ratio > MERGE_MAX_SIZE_RATIO) continue
                    working[i] = a.union(b)
                    working.removeAt(j)
                    merged = true
                    break@outer
                }
            }
        }
        return working
    }

    /** Right-to-left, top-to-bottom: the order a Japanese page is read in. */
    private fun readingOrder(): Comparator<BoxF> = compareBy<BoxF> { (it.top / ROW_BUCKET).toInt() }
        .thenByDescending { it.right }

    override fun close() = model.close()

    private companion object {
        const val MERGE_SLOP = 0.35f
        const val MERGE_MAX_SIZE_RATIO = 3f
        const val ROW_BUCKET = 64f
    }
}

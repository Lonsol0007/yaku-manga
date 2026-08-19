package yaku.translation.engine.onnx

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import yaku.translation.model.BoxF

/** Shared bitmap -> NCHW float tensor plumbing for the detector and the recogniser. */
internal object ImageTensors {

    /**
     * Letterboxes [source] into a [size] x [size] square and returns it as NCHW float data.
     *
     * Letterboxing rather than stretching matters here: manga panels are wildly non-square, and a
     * stretched bubble makes vertical kana look like horizontal ones to the recogniser.
     *
     * @return the tensor data plus the scale and padding needed to map coordinates back.
     */
    fun letterbox(
        source: Bitmap,
        size: Int,
        mean: List<Float>,
        std: List<Float>,
    ): LetterboxResult {
        val scale = minOf(size.toFloat() / source.width, size.toFloat() / source.height)
        val scaledWidth = (source.width * scale).toInt().coerceAtLeast(1)
        val scaledHeight = (source.height * scale).toInt().coerceAtLeast(1)
        val padX = (size - scaledWidth) / 2
        val padY = (size - scaledHeight) / 2

        val canvasBitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(canvasBitmap).apply {
            drawColor(Color.WHITE)
            drawBitmap(
                source,
                Rect(0, 0, source.width, source.height),
                RectF(
                    padX.toFloat(),
                    padY.toFloat(),
                    (padX + scaledWidth).toFloat(),
                    (padY + scaledHeight).toFloat(),
                ),
                null,
            )
        }

        val data = toNchw(canvasBitmap, mean, std)
        canvasBitmap.recycle()
        return LetterboxResult(data, size, scale, padX.toFloat(), padY.toFloat())
    }

    /** Straight resize with no padding - used for text crops, which are already tightly framed. */
    fun resized(
        source: Bitmap,
        size: Int,
        mean: List<Float>,
        std: List<Float>,
    ): FloatArray {
        val scaled = source.scale(size, size)
        val data = toNchw(scaled, mean, std)
        if (scaled !== source) scaled.recycle()
        return data
    }

    private fun Bitmap.scale(width: Int, height: Int): Bitmap =
        if (this.width == width && this.height == height) {
            this
        } else {
            Bitmap.createScaledBitmap(this, width, height, true)
        }

    private fun toNchw(bitmap: Bitmap, mean: List<Float>, std: List<Float>): FloatArray {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val plane = width * height
        val out = FloatArray(3 * plane)
        val meanR = mean.getOrElse(0) { 0.5f }
        val meanG = mean.getOrElse(1) { 0.5f }
        val meanB = mean.getOrElse(2) { 0.5f }
        val stdR = std.getOrElse(0) { 0.5f }
        val stdG = std.getOrElse(1) { 0.5f }
        val stdB = std.getOrElse(2) { 0.5f }

        for (i in 0 until plane) {
            val pixel = pixels[i]
            val r = ((pixel shr 16) and 0xFF) / 255f
            val g = ((pixel shr 8) and 0xFF) / 255f
            val b = (pixel and 0xFF) / 255f
            out[i] = (r - meanR) / stdR
            out[plane + i] = (g - meanG) / stdG
            out[2 * plane + i] = (b - meanB) / stdB
        }
        return out
    }

    data class LetterboxResult(
        val data: FloatArray,
        val size: Int,
        val scale: Float,
        val padX: Float,
        val padY: Float,
    ) {
        /** Maps a box in letterboxed model space back to original bitmap coordinates. */
        fun unmap(box: BoxF, sourceWidth: Int, sourceHeight: Int): BoxF = BoxF(
            left = ((box.left - padX) / scale).coerceIn(0f, sourceWidth.toFloat()),
            top = ((box.top - padY) / scale).coerceIn(0f, sourceHeight.toFloat()),
            right = ((box.right - padX) / scale).coerceIn(0f, sourceWidth.toFloat()),
            bottom = ((box.bottom - padY) / scale).coerceIn(0f, sourceHeight.toFloat()),
        )

        override fun equals(other: Any?): Boolean =
            this === other || (
                other is LetterboxResult && data.contentEquals(other.data) &&
                    size == other.size && scale == other.scale && padX == other.padX && padY == other.padY
                )

        override fun hashCode(): Int =
            data.contentHashCode() * 31 + size * 31 + scale.hashCode() * 31 + padX.hashCode() * 31 + padY.hashCode()
    }
}

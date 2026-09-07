package yaku.translation.engine

import android.graphics.Bitmap
import yaku.translation.model.TextBlock
import java.io.Closeable

/**
 * Finds the regions of a page that hold text.
 *
 * Two kinds exist and they answer in very different ways. A segmentation detector returns a
 * probability map that has to be thresholded and grouped into boxes, and knows nothing about
 * speech balloons. A detection model returns the boxes directly, and one trained on comics
 * returns the balloons alongside them - which is worth far more than the boxes, because the
 * balloon is where the translation has to be set.
 */
interface TextDetector : Closeable {
    fun detect(page: Bitmap): List<TextBlock>
}

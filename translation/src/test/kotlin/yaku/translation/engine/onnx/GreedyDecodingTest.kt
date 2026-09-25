package yaku.translation.engine.onnx

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.FloatBuffer

/**
 * Greedy decoding picks each next token from the decoder's logits for the final position only.
 * Reading an earlier row would repeat the sentence's start instead of continuing it.
 */
class GreedyDecodingTest {

    @Test
    fun `picks the best token of the final step, not of an earlier one`() {
        // Two steps over a three-token vocabulary: step one prefers token 2, step two token 0.
        val logits = FloatBuffer.wrap(floatArrayOf(0f, 1f, 9f, 5f, 1f, 2f))

        argmaxOfLastRow(logits, rowLength = 3) shouldBe 0
    }

    @Test
    fun `reads the only row of a single step`() {
        val logits = FloatBuffer.wrap(floatArrayOf(-3f, -1f, -2f))

        argmaxOfLastRow(logits, rowLength = 3) shouldBe 1
    }

    @Test
    fun `keeps the first of equal best values`() {
        val logits = FloatBuffer.wrap(floatArrayOf(4f, 4f, 1f))

        argmaxOfLastRow(logits, rowLength = 3) shouldBe 0
    }
}

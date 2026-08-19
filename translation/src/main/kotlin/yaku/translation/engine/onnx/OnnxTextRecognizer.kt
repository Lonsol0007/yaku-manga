package yaku.translation.engine.onnx

import android.graphics.Bitmap
import kotlinx.serialization.json.Json
import yaku.translation.model.BoxF
import yaku.translation.store.PackConfig
import java.io.Closeable
import java.io.File

/**
 * Vision-encoder / text-decoder OCR, the manga-ocr architecture.
 *
 * The encoder turns a 224x224 crop into a sequence of hidden states; the decoder autoregressively
 * emits token ids conditioned on those states. Decoding is plain greedy - beam search roughly
 * triples the cost for a barely measurable quality gain on short bubble text, and this runs
 * once per bubble on a phone.
 */
class OnnxTextRecognizer(
    private val encoder: OrtModel,
    private val decoder: OrtModel,
    private val vocab: List<String>,
    private val config: PackConfig,
) : Closeable {

    private val encoderInput = encoder.resolveInput("pixel_values", "input", "image")
    private val encoderOutput = encoder.resolveOutput("last_hidden_state", "output", "hidden_states")
    private val decoderIdsInput = decoder.resolveInput("input_ids", "decoder_input_ids", "ids")
    private val decoderStateInput = decoder.inputNames
        .firstOrNull { it == "encoder_hidden_states" || it == "encoder_outputs" }
        ?: decoder.inputNames.firstOrNull { it != decoderIdsInput }
        ?: error("Recogniser decoder has no encoder-state input")
    private val decoderOutput = decoder.resolveOutput("logits", "output")

    private val bosId = vocab.indexOf(BOS).takeIf { it >= 0 } ?: 2
    private val eosId = vocab.indexOf(EOS).takeIf { it >= 0 } ?: 3

    fun recognize(page: Bitmap, box: BoxF): String {
        val crop = cropOf(page, box) ?: return ""
        return try {
            val pixels = ImageTensors.resized(
                crop,
                config.recognizerInputSize,
                config.recognizerMean,
                config.recognizerStd,
            )
            val side = config.recognizerInputSize.toLong()
            val encoded = encoder.floatTensor(pixels, longArrayOf(1, 3, side, side)).use { tensor ->
                encoder.run(mapOf(encoderInput to tensor), encoderOutput) { data, shape ->
                    EncoderState(data, shape)
                }
            }
            decodeGreedy(encoded)
        } finally {
            crop.recycle()
        }
    }

    private fun decodeGreedy(state: EncoderState): String {
        val ids = ArrayList<Long>(config.recognizerMaxTokens)
        ids.add(bosId.toLong())

        // The encoder state is identical at every step, so build the tensor once.
        decoder.floatTensor(state.data, state.shape).use { stateTensor ->
            repeat(config.recognizerMaxTokens) {
                val next = decoder.longTensor(ids.toLongArray(), longArrayOf(1, ids.size.toLong()))
                    .use { idsTensor ->
                        decoder.run(
                            mapOf(decoderIdsInput to idsTensor, decoderStateInput to stateTensor),
                            decoderOutput,
                        ) { logits, shape -> argmaxLastStep(logits, shape) }
                    }
                if (next == eosId) return detokenize(ids)
                ids.add(next.toLong())
            }
        }
        return detokenize(ids)
    }

    /** logits arrive as [1, steps, vocab]; we only care about the final step. */
    private fun argmaxLastStep(logits: FloatArray, shape: LongArray): Int {
        val vocabSize = shape.last().toInt()
        val offset = logits.size - vocabSize
        var best = 0
        var bestValue = Float.NEGATIVE_INFINITY
        for (i in 0 until vocabSize) {
            val value = logits[offset + i]
            if (value > bestValue) {
                bestValue = value
                best = i
            }
        }
        return best
    }

    private fun detokenize(ids: List<Long>): String {
        val builder = StringBuilder()
        for (id in ids) {
            val token = vocab.getOrNull(id.toInt()) ?: continue
            if (token in SPECIAL_TOKENS) continue
            builder.append(token.removePrefix("##"))
        }
        return builder.toString().trim()
    }

    private fun cropOf(page: Bitmap, box: BoxF): Bitmap? {
        val left = box.left.toInt().coerceIn(0, page.width - 1)
        val top = box.top.toInt().coerceIn(0, page.height - 1)
        val right = box.right.toInt().coerceIn(left + 1, page.width)
        val bottom = box.bottom.toInt().coerceIn(top + 1, page.height)
        if (right - left < 2 || bottom - top < 2) return null
        return Bitmap.createBitmap(page, left, top, right - left, bottom - top)
    }

    override fun close() {
        encoder.close()
        decoder.close()
    }

    private class EncoderState(val data: FloatArray, val shape: LongArray)

    companion object {
        private const val BOS = "[CLS]"
        private const val EOS = "[SEP]"
        private val SPECIAL_TOKENS = setOf("[CLS]", "[SEP]", "[PAD]", "[UNK]", "<s>", "</s>", "<pad>", "<unk>")

        /** Recogniser vocabularies are a flat JSON array of token strings in id order. */
        fun loadVocab(file: File, json: Json = Json { ignoreUnknownKeys = true }): List<String> =
            json.decodeFromString(file.readText())
    }
}

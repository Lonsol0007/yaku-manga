package yaku.translation.engine.onnx

import yaku.translation.engine.onnx.tokenizer.UnigramTokenizer
import yaku.translation.model.TranslationLanguage
import yaku.translation.store.PackConfig
import java.io.Closeable

/**
 * Seq2seq machine translation over ONNX (NLLB-200 distilled and opus-MT both fit this shape).
 *
 * Encoder runs once per source string; the decoder runs autoregressively. As with the recogniser
 * this is greedy decoding without a KV cache, which means step *t* re-attends over *t* tokens.
 * For bubble-length text (rarely over 40 tokens) that is the right trade against carrying cache
 * tensors through the graph, which roughly doubles the exported model's input surface.
 */
class OnnxTranslator(
    private val encoder: OrtModel,
    private val decoder: OrtModel,
    private val tokenizer: UnigramTokenizer,
    private val config: PackConfig,
) : Closeable {

    private val encoderIdsInput = encoder.resolveInput("input_ids", "ids")
    private val encoderMaskInput = encoder.inputNames.firstOrNull { it == "attention_mask" }
    private val encoderOutput = encoder.resolveOutput("last_hidden_state", "output", "encoder_outputs")

    private val decoderIdsInput = decoder.resolveInput("decoder_input_ids", "input_ids", "ids")
    private val decoderStateInput = decoder.inputNames
        .firstOrNull { it == "encoder_hidden_states" || it == "encoder_outputs" }
        ?: decoder.inputNames.firstOrNull { it != decoderIdsInput }
        ?: error("Translator decoder has no encoder-state input")
    private val decoderMaskInput = decoder.inputNames.firstOrNull { it == "encoder_attention_mask" }
    private val decoderOutput = decoder.resolveOutput("logits", "output")

    private val eosId = tokenizer.idOf(config.translatorEosToken) ?: tokenizer.eosId
    private val bosId = tokenizer.idOf(config.translatorBosToken) ?: tokenizer.bosId

    fun translate(
        text: String,
        source: TranslationLanguage,
        target: TranslationLanguage,
    ): String {
        if (text.isBlank()) return ""

        val sourceIds = buildSourceIds(text, source)
        if (sourceIds.isEmpty()) return ""

        val length = sourceIds.size.toLong()
        val mask = LongArray(sourceIds.size) { 1L }

        val encoderInputs = buildMap {
            put(encoderIdsInput, encoder.longTensor(sourceIds, longArrayOf(1, length)))
            encoderMaskInput?.let { put(it, encoder.longTensor(mask, longArrayOf(1, length))) }
        }
        val state = try {
            encoder.run(encoderInputs, encoderOutput) { data, shape -> EncoderState(data, shape) }
        } finally {
            encoderInputs.values.forEach { it.close() }
        }

        return decodeGreedy(state, mask, target)
    }

    private fun buildSourceIds(text: String, source: TranslationLanguage): LongArray {
        val encoded = tokenizer.encode(text)
        if (encoded.isEmpty()) return LongArray(0)

        val ids = ArrayList<Long>(encoded.size + 2)
        // NLLB expects the *source* language token to lead the encoder input.
        if (config.translatorUsesLanguageToken) {
            tokenizer.idOf(source.modelCode)?.let { ids.add(it.toLong()) }
        }
        encoded.forEach { ids.add(it.toLong()) }
        ids.add(eosId.toLong())
        return ids.toLongArray()
    }

    private fun decodeGreedy(
        state: EncoderState,
        encoderMask: LongArray,
        target: TranslationLanguage,
    ): String {
        val ids = ArrayList<Long>(config.translatorMaxTokens)
        ids.add(eosId.toLong()) // decoder_start_token_id for NLLB/M2M is </s>
        if (config.translatorUsesLanguageToken) {
            val languageId = tokenizer.idOf(target.modelCode)
            if (languageId != null) ids.add(languageId.toLong()) else ids.add(bosId.toLong())
        }
        val promptLength = ids.size

        val stateTensor = decoder.floatTensor(state.data, state.shape)
        val maskTensor = decoderMaskInput?.let {
            decoder.longTensor(encoderMask, longArrayOf(1, encoderMask.size.toLong()))
        }

        try {
            repeat(config.translatorMaxTokens) {
                val next = decoder.longTensor(ids.toLongArray(), longArrayOf(1, ids.size.toLong()))
                    .use { idsTensor ->
                        val inputs = buildMap {
                            put(decoderIdsInput, idsTensor)
                            put(decoderStateInput, stateTensor)
                            if (decoderMaskInput != null && maskTensor != null) {
                                put(decoderMaskInput, maskTensor)
                            }
                        }
                        decoder.run(inputs, decoderOutput) { logits, shape -> argmaxLastStep(logits, shape) }
                    }
                if (next == eosId) return tokenizer.decode(ids.drop(promptLength).map { it.toInt() }.toIntArray())
                ids.add(next.toLong())
            }
        } finally {
            stateTensor.close()
            maskTensor?.close()
        }
        return tokenizer.decode(ids.drop(promptLength).map { it.toInt() }.toIntArray())
    }

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

    override fun close() {
        encoder.close()
        decoder.close()
    }

    private class EncoderState(val data: FloatArray, val shape: LongArray)
}

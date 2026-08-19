package yaku.translation.engine.onnx.tokenizer

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.text.Normalizer

/**
 * Vocabulary file format expected next to a translator model.
 *
 * This is the shape produced by dumping a SentencePiece unigram model to JSON - a flat list of
 * `[piece, log_probability]` pairs in id order, plus the ids of the special tokens. Keeping it as
 * plain JSON avoids linking the SentencePiece native library, which would add another ~1.5 MB of
 * .so per ABI for something we can do in a few hundred lines of Kotlin.
 */
@Serializable
data class SentencePieceVocab(
    @SerialName("model_type") val modelType: String = "unigram",
    val pieces: List<VocabPiece>,
    @SerialName("unk_id") val unkId: Int = 0,
    @SerialName("bos_id") val bosId: Int = 1,
    @SerialName("eos_id") val eosId: Int = 2,
    @SerialName("pad_id") val padId: Int = 3,
) {
    companion object {
        fun load(file: File, json: Json = Json { ignoreUnknownKeys = true }): SentencePieceVocab =
            json.decodeFromString(file.readText())
    }
}

@Serializable
data class VocabPiece(
    val piece: String,
    val score: Float = 0f,
)

/**
 * A SentencePiece unigram tokenizer.
 *
 * Encoding runs the standard Viterbi segmentation: for each prefix of the (space-escaped) input,
 * find the highest-scoring path of vocabulary pieces reaching it. Characters that no piece covers
 * fall back to the unknown token so a stray emoji in a speech bubble can't derail a whole page.
 */
class UnigramTokenizer(private val vocab: SentencePieceVocab) {

    private val pieceToId: Map<String, Int> =
        vocab.pieces.withIndex().associate { (index, p) -> p.piece to index }

    private val scores: FloatArray = FloatArray(vocab.pieces.size) { vocab.pieces[it].score }

    private val maxPieceLength: Int = vocab.pieces.maxOfOrNull { it.piece.length } ?: 1

    val unkId: Int get() = vocab.unkId
    val bosId: Int get() = vocab.bosId
    val eosId: Int get() = vocab.eosId
    val padId: Int get() = vocab.padId
    val size: Int get() = vocab.pieces.size

    fun idOf(piece: String): Int? = pieceToId[piece]

    fun pieceOf(id: Int): String? = vocab.pieces.getOrNull(id)?.piece

    fun encode(text: String): IntArray {
        val normalized = normalize(text)
        if (normalized.isEmpty()) return IntArray(0)

        val n = normalized.length
        // best[i] = score of the best segmentation of normalized[0until i]
        val best = FloatArray(n + 1) { Float.NEGATIVE_INFINITY }
        val backPiece = IntArray(n + 1) { -1 }
        val backStart = IntArray(n + 1) { -1 }
        best[0] = 0f

        for (end in 1..n) {
            val minStart = maxOf(0, end - maxPieceLength)
            for (start in minStart until end) {
                if (best[start] == Float.NEGATIVE_INFINITY) continue
                val id = pieceToId[normalized.substring(start, end)] ?: continue
                val candidate = best[start] + scores[id]
                if (candidate > best[end]) {
                    best[end] = candidate
                    backPiece[end] = id
                    backStart[end] = start
                }
            }
            if (best[end] == Float.NEGATIVE_INFINITY) {
                // No piece ends here; consume a single character as <unk>.
                val start = end - 1
                if (best[start] != Float.NEGATIVE_INFINITY) {
                    best[end] = best[start] + UNK_PENALTY
                    backPiece[end] = vocab.unkId
                    backStart[end] = start
                }
            }
        }

        val ids = ArrayList<Int>()
        var cursor = n
        while (cursor > 0) {
            val id = backPiece[cursor]
            val start = backStart[cursor]
            if (id < 0 || start < 0) break
            ids.add(id)
            cursor = start
        }
        ids.reverse()
        return ids.toIntArray()
    }

    fun decode(ids: IntArray): String {
        val builder = StringBuilder()
        for (id in ids) {
            if (id == vocab.eosId || id == vocab.bosId || id == vocab.padId) continue
            val piece = vocab.pieces.getOrNull(id)?.piece ?: continue
            if (piece.startsWith(SPECIAL_PREFIX) && piece.endsWith(SPECIAL_SUFFIX)) continue
            builder.append(piece)
        }
        return builder.toString().replace(SPACE_MARKER, ' ').trim()
    }

    /**
     * NFKC-fold, collapse whitespace, then escape spaces the way SentencePiece does.
     *
     * The NFKC pass is not cosmetic. SentencePiece vocabularies are trained on normalised text,
     * so full-width punctuation is absent from them: `！？～…－（）` all miss and fall through to
     * `<unk>`, while their ASCII forms are present. The recogniser emits full-width punctuation
     * because that is what is printed in the bubble, which means without this nearly every line
     * of dialogue loses its terminal mark.
     *
     * Japanese-specific punctuation (`「」`, `、`, `。`, `ー`) is in the vocabulary already and
     * NFKC leaves it alone, so nothing is lost by folding.
     */
    private fun normalize(text: String): String {
        val folded = Normalizer.normalize(text, Normalizer.Form.NFKC)
        val collapsed = folded.trim().replace(WHITESPACE_RUN, " ")
        if (collapsed.isEmpty()) return ""
        return "$SPACE_MARKER" + collapsed.replace(' ', SPACE_MARKER)
    }

    private companion object {
        const val SPACE_MARKER = '▁' // lower one eighth block, SentencePiece's space escape
        const val UNK_PENALTY = -10f
        const val SPECIAL_PREFIX = "<"
        const val SPECIAL_SUFFIX = ">"
        val WHITESPACE_RUN = Regex("\\s+")
    }
}

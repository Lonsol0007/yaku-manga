package yaku.translation.store

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Describes the ONNX assets a translation pack is made of.
 *
 * Nothing here is baked into the APK: the app ships with no weights at all, and the user
 * chooses a pack to download. Tensor names live in the manifest rather than in code because
 * they differ between export toolchains - a manga-ocr export from Optimum names its encoder
 * output `last_hidden_state`, a hand-rolled export may not.
 */
@Serializable
data class ModelManifest(
    val version: Int = 1,
    val packs: List<ModelPack> = emptyList(),
)

@Serializable
data class ModelPack(
    val id: String,
    val name: String,
    val description: String = "",
    @SerialName("source_languages") val sourceLanguages: List<String> = emptyList(),
    @SerialName("target_languages") val targetLanguages: List<String> = emptyList(),
    val detector: ModelFile,
    @SerialName("recognizer_encoder") val recognizerEncoder: ModelFile,
    @SerialName("recognizer_decoder") val recognizerDecoder: ModelFile,
    @SerialName("recognizer_vocab") val recognizerVocab: ModelFile,
    @SerialName("translator_encoder") val translatorEncoder: ModelFile,
    @SerialName("translator_decoder") val translatorDecoder: ModelFile,
    @SerialName("translator_vocab") val translatorVocab: ModelFile,
    val config: PackConfig = PackConfig(),
    /**
     * Manifest this pack was offered by. Absent from manifests themselves - the repository
     * stamps it in on fetch and stores it in the installed descriptor.
     *
     * Two sources can name a pack `ja-en-base` and mean different things, and packs install into
     * a directory named after their id. Recording where one came from is what lets an install
     * refuse to write over an unrelated pack that happens to share a name.
     */
    val source: String = "",
) {
    val files: List<ModelFile>
        get() = listOf(
            detector,
            recognizerEncoder,
            recognizerDecoder,
            recognizerVocab,
            translatorEncoder,
            translatorDecoder,
            translatorVocab,
        )

    val totalBytes: Long get() = files.sumOf { it.sizeBytes }
}

@Serializable
data class ModelFile(
    /** File name on disk, unique within a pack. */
    val name: String,
    val url: String,
    @SerialName("size_bytes") val sizeBytes: Long = 0L,
    /** Lowercase hex SHA-256. Downloads that do not match are discarded. */
    val sha256: String,
)

/**
 * Per-pack knobs. Defaults match a DBNet detector + manga-ocr recogniser + NLLB translator,
 * which is the combination the reference pack uses.
 */
@Serializable
data class PackConfig(
    /**
     * Which generation of tuning the detector settings below belong to.
     *
     * A descriptor is written once at download time and read back verbatim forever, so without
     * a marker there is no way to tell a pack that was built with deliberate settings from one
     * carrying values that were simply the defaults of the day. Packs older than
     * [TUNED_DETECTOR] have their detector trio replaced on load.
     */
    @SerialName("config_version") val configVersion: Int = 0,

    // -- detector
    @SerialName("detector_input_size") val detectorInputSize: Int = 960,
    @SerialName("detector_input_name") val detectorInputName: String = "input",
    @SerialName("detector_output_name") val detectorOutputName: String = "output",
    /**
     * Deliberately low. Swept against a page with known text: at 0.3 the detector found
     * 0-2 of 4 speech bubbles, at 0.15 it found all four. Manga lettering sits on white
     * with thin strokes, and a DBNet trained on documents reports it far less confidently
     * than it reports printed paragraphs.
     */
    @SerialName("detector_threshold") val detectorThreshold: Float = 0.15f,
    /** 0.08 clipped the last characters off horizontal lines; 0.15 keeps them. */
    @SerialName("detector_box_expand") val detectorBoxExpand: Float = 0.15f,
    @SerialName("detector_min_area_ratio") val detectorMinAreaRatio: Float = 0.00015f,
    /**
     * How far apart two boxes may sit and still be merged, as a multiple of the smaller box.
     *
     * Detectors emit one blob per glyph cluster, not per bubble, and Japanese is usually set
     * vertically, so a line of dialogue arrives as a stack of separate boxes with a full
     * character of leading between them. Feeding those to the recogniser one glyph at a time
     * produces plausible single words, costs a full inference pass each, and loses the context
     * that makes the translation mean anything.
     *
     * Because the slop scales with the *smaller* box, two 33px fragments of one column could
     * only reach 66px apart while the real gap was 146px - so they never merged. 3.5 closes
     * that without letting a caption swallow a neighbouring sound effect.
     */
    @SerialName("detector_merge_slop") val detectorMergeSlop: Float = 3.5f,
    /** Boxes whose heights differ by more than this are never merged. */
    @SerialName("detector_merge_max_size_ratio") val detectorMergeMaxSizeRatio: Float = 3f,
    @SerialName("detector_mean") val detectorMean: List<Float> = listOf(0.485f, 0.456f, 0.406f),
    @SerialName("detector_std") val detectorStd: List<Float> = listOf(0.229f, 0.224f, 0.225f),

    // -- recogniser
    @SerialName("recognizer_input_size") val recognizerInputSize: Int = 224,
    @SerialName("recognizer_max_tokens") val recognizerMaxTokens: Int = 64,
    @SerialName("recognizer_mean") val recognizerMean: List<Float> = listOf(0.5f, 0.5f, 0.5f),
    @SerialName("recognizer_std") val recognizerStd: List<Float> = listOf(0.5f, 0.5f, 0.5f),

    // -- translator
    @SerialName("translator_max_tokens") val translatorMaxTokens: Int = 128,
    @SerialName("translator_bos_token") val translatorBosToken: String = "<s>",
    @SerialName("translator_eos_token") val translatorEosToken: String = "</s>",
    @SerialName("translator_pad_token") val translatorPadToken: String = "<pad>",
    @SerialName("translator_unk_token") val translatorUnkToken: String = "<unk>",
    /** NLLB-style models need the target language as the first decoder token. */
    @SerialName("translator_uses_language_token") val translatorUsesLanguageToken: Boolean = true,
    /**
     * Token the decoder is primed with. NLLB and M2M start from the EOS token, which is the
     * fallback; Marian/opus-MT starts from the pad token instead. Priming with the wrong one
     * yields fluent nonsense rather than an obvious failure, so it is worth setting explicitly.
     */
    @SerialName("translator_decoder_start_token") val translatorDecoderStartToken: String? = null,
) {
    companion object {
        /**
         * Generation that carries the swept detector settings. Raise this, and update the
         * defaults above, to retune every installed pack on the next launch.
         */
        const val TUNED_DETECTOR = 1
    }
}

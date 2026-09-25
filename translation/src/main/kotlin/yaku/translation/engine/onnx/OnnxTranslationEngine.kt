package yaku.translation.engine.onnx

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import logcat.LogPriority
import yaku.core.common.util.system.logcat
import yaku.translation.engine.TextDetector
import yaku.translation.engine.TranslationEngine
import yaku.translation.engine.onnx.tokenizer.SentencePieceVocab
import yaku.translation.engine.onnx.tokenizer.UnigramTokenizer
import yaku.translation.model.PageTranslation
import yaku.translation.model.TranslationLanguage
import yaku.translation.model.TranslationProgress
import yaku.translation.store.ModelPack
import yaku.translation.store.ModelRepository

/**
 * The default engine: DBNet detection -> manga-ocr recognition -> NLLB translation, all local.
 *
 * Sessions are created lazily on first use and guarded by a mutex, because the reader can ask for
 * two pages at once when the user flips quickly and ONNX Runtime sessions are not safe to
 * initialise concurrently.
 */
class OnnxTranslationEngine(
    private val pack: ModelPack,
    private val repository: ModelRepository,
    private val threads: Int = DEFAULT_THREADS,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : TranslationEngine {

    override val id: String get() = pack.id

    private val loadLock = Mutex()

    private var detector: TextDetector? = null
    private var recognizer: OnnxTextRecognizer? = null
    private var translator: OnnxTranslator? = null

    override fun isReady(): Boolean = repository.isInstalled(pack)

    override suspend fun warmUp() = withContext(Dispatchers.Default) {
        loadLock.withLock { loadIfNeeded() }
    }

    private fun file(name: String) = repository.installedFile(pack.id, name)

    private fun loadIfNeeded() {
        if (detector != null && recognizer != null && translator != null) return

        // A pack can fail part way through loading - a missing or corrupt file, or a graph
        // without the input a stage expects - after other sessions have already opened. Each
        // holds tens to hundreds of MB of native memory that nothing else would free, and the
        // next page would open them all again. So nothing is kept unless everything loads.
        val opened = mutableListOf<OrtModel>()
        fun openModel(name: String) = OrtModel.open(file(name), threads).also { opened += it }
        try {
            // Both kinds answer the same question and nothing downstream can tell them apart,
            // but they arrive at it so differently that they cannot share an implementation: one
            // thresholds a map and groups blobs, the other reads boxes the model already drew.
            val detectorModel = openModel(pack.detector.name)
            val newDetector = if (pack.config.detectorKind == COMIC_DETECTOR) {
                OnnxComicDetector(detectorModel, pack.config)
            } else {
                OnnxTextDetector(detectorModel, pack.config)
            }
            val newRecognizer = OnnxTextRecognizer(
                encoder = openModel(pack.recognizerEncoder.name),
                decoder = openModel(pack.recognizerDecoder.name),
                vocab = OnnxTextRecognizer.loadVocab(file(pack.recognizerVocab.name), json),
                config = pack.config,
            )
            val newTranslator = OnnxTranslator(
                encoder = openModel(pack.translatorEncoder.name),
                decoder = openModel(pack.translatorDecoder.name),
                tokenizer = UnigramTokenizer(SentencePieceVocab.load(file(pack.translatorVocab.name), json)),
                config = pack.config,
            )
            detector = newDetector
            recognizer = newRecognizer
            translator = newTranslator
        } catch (e: Throwable) {
            opened.forEach { it.close() }
            throw e
        }
    }

    override suspend fun translatePage(
        page: Bitmap,
        source: TranslationLanguage,
        target: TranslationLanguage,
        onProgress: (TranslationProgress) -> Unit,
    ): PageTranslation = withContext(Dispatchers.Default) {
        val started = System.currentTimeMillis()

        onProgress(TranslationProgress.LoadingModels)
        loadLock.withLock { loadIfNeeded() }
        val detector = detector!!
        val recognizer = recognizer!!
        val translator = translator!!
        val loaded = System.currentTimeMillis()

        onProgress(TranslationProgress.Detecting)
        val detected = detector.detect(page)
        val detectedAt = System.currentTimeMillis()

        // Every block is a full encoder-decoder pass and a page can hold dozens, which is tens of
        // seconds. Checking between them lets a page the reader has already left hand the cores -
        // and PageTranslator's gate - to the page it moved to.
        val recognized = detected.mapIndexed { index, block ->
            ensureActive()
            onProgress(TranslationProgress.Recognizing(index, detected.size))
            val text = runCatching { recognizer.recognize(page, block.box) }
                .onFailure { logcat(LogPriority.WARN, it) { "Recognition failed for a block" } }
                .getOrDefault("")
            // Two letters, not merely non-blank. A bubble outline reads as "(" often enough
            // that it was being translated and drawn as a box with a bracket in it, and on a
            // page of real artwork the detector returns single glyphs it has torn off a column
            // - each one arrives as a plausible word and is set on the page as if it were a
            // line of dialogue. A one-character line does exist, but it is rarer than the
            // failure, so losing the odd interjection is the better trade.
            block.copy(sourceText = text.takeIf { it.count(Char::isLetter) >= MIN_SOURCE_LETTERS })
        }.filter { it.sourceText != null }
        val recognizedAt = System.currentTimeMillis()

        val translated = recognized.mapIndexed { index, block ->
            ensureActive()
            onProgress(TranslationProgress.Translating(index, recognized.size))
            val text = runCatching { translator.translate(block.sourceText!!, source, target) }
                .onFailure { logcat(LogPriority.WARN, it) { "Translation failed for a block" } }
                .getOrDefault("")
            val cleaned = tidy(text)
            block.copy(translatedText = cleaned.takeIf { it.isNotBlank() && isProportionate(it, block.sourceText!!) })
        }
        val translatedAt = System.currentTimeMillis()

        // Per stage, so a change aimed at one of them can be measured on a device instead of assumed.
        logcat {
            "Page took ${translatedAt - started}ms: load ${loaded - started}, detect ${detectedAt - loaded}, " +
                "recognise ${recognizedAt - detectedAt} (${detected.size} regions), " +
                "translate ${translatedAt - recognizedAt} (${recognized.size} lines)"
        }

        PageTranslation(
            pageWidth = page.width,
            pageHeight = page.height,
            blocks = translated,
            sourceLanguage = source,
            targetLanguage = target,
            elapsedMillis = translatedAt - started,
        ).also { onProgress(TranslationProgress.Done(it)) }
    }

    /**
     * Rejects a translation far longer than its source could account for.
     *
     * A decoder that loses its way does not stop - it repeats until it hits the token limit,
     * and returns a long, fluent, entirely invented passage. Measured on a scanned page, one
     * box turned 63 characters of Japanese into 379 of English. Japanese to English roughly
     * triples in length, so anything past five times the source plus a margin is the decoder
     * talking to itself, and printing it over the artwork is worse than leaving the box alone.
     */
    private fun isProportionate(translated: String, source: String): Boolean =
        translated.length <= source.length * MAX_LENGTH_RATIO + LENGTH_ALLOWANCE

    /**
     * Removes the subtitle conventions the translator picked up from its training data.
     *
     * opus-MT was trained largely on film subtitles, so it prefixes lines with a dialogue dash
     * and, when the source is a single short interjection, often emits the same sentence twice -
     * a plain greeting came back as "- Good morning. - Good morning.". Both arrive as ordinary
     * output tokens, indistinguishable from content until the sentence is read as a whole, so
     * this is the first point that can tell them apart.
     */
    private fun tidy(text: String): String {
        val sentences = text.split(SENTENCE_BREAK)
            .map { it.trim().removePrefix("-").trim() }
            .filter { it.isNotEmpty() }
        if (sentences.isEmpty()) return text.trim()
        // Collapse only when every sentence is the same one. Repetition for emphasis is rarer
        // in dialogue than this failure is, but a translation of several distinct sentences
        // has to survive intact.
        return sentences.distinct().singleOrNull() ?: sentences.joinToString(" ")
    }

    override fun close() {
        detector?.close()
        recognizer?.close()
        translator?.close()
        detector = null
        recognizer = null
        translator = null
    }

    companion object {
        /** [PackConfig.detectorKind] of a detector that returns boxes and balloons. */
        private const val COMIC_DETECTOR = "comic"

        /** Below this the "text" is a fragment the detector tore off, not a line of dialogue. */
        private const val MIN_SOURCE_LETTERS = 2

        /** Japanese to English roughly triples; five times over is a decoder that has looped. */
        private const val MAX_LENGTH_RATIO = 5
        private const val LENGTH_ALLOWANCE = 20

        /** A sentence end, or the dash opus-MT uses to open a new speaker's line. */
        private val SENTENCE_BREAK = Regex("""(?<=[.!?])\s+|\s+-\s+""")

        val DEFAULT_THREADS: Int get() = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)
    }
}

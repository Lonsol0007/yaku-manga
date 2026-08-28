package yaku.translation.engine.onnx

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import logcat.LogPriority
import yaku.core.common.util.system.logcat
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

    private var detector: OnnxTextDetector? = null
    private var recognizer: OnnxTextRecognizer? = null
    private var translator: OnnxTranslator? = null

    override fun isReady(): Boolean = repository.isInstalled(pack)

    override suspend fun warmUp() = withContext(Dispatchers.Default) {
        loadLock.withLock { loadIfNeeded() }
    }

    private fun file(name: String) = repository.installedFile(pack.id, name)

    private fun loadIfNeeded() {
        if (detector != null && recognizer != null && translator != null) return

        detector = OnnxTextDetector(
            model = OrtModel.open(file(pack.detector.name), threads),
            config = pack.config,
        )
        recognizer = OnnxTextRecognizer(
            encoder = OrtModel.open(file(pack.recognizerEncoder.name), threads),
            decoder = OrtModel.open(file(pack.recognizerDecoder.name), threads),
            vocab = OnnxTextRecognizer.loadVocab(file(pack.recognizerVocab.name), json),
            config = pack.config,
        )
        translator = OnnxTranslator(
            encoder = OrtModel.open(file(pack.translatorEncoder.name), threads),
            decoder = OrtModel.open(file(pack.translatorDecoder.name), threads),
            tokenizer = UnigramTokenizer(SentencePieceVocab.load(file(pack.translatorVocab.name), json)),
            config = pack.config,
        )
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

        onProgress(TranslationProgress.Detecting)
        val detected = detector.detect(page)

        val recognized = detected.mapIndexed { index, block ->
            onProgress(TranslationProgress.Recognizing(index, detected.size))
            val text = runCatching { recognizer.recognize(page, block.box) }
                .onFailure { logcat(LogPriority.WARN, it) { "Recognition failed for a block" } }
                .getOrDefault("")
            // Requires an actual letter, not merely non-blank. A bubble outline reads as
            // "(" often enough that it was being translated and drawn as a white box with a
            // bracket in it - a full inference pass spent to deface the page.
            block.copy(sourceText = text.takeIf { candidate -> candidate.any(Char::isLetter) })
        }.filter { it.sourceText != null }

        val translated = recognized.mapIndexed { index, block ->
            onProgress(TranslationProgress.Translating(index, recognized.size))
            val text = runCatching { translator.translate(block.sourceText!!, source, target) }
                .onFailure { logcat(LogPriority.WARN, it) { "Translation failed for a block" } }
                .getOrDefault("")
            block.copy(translatedText = tidy(text).takeIf { it.isNotBlank() })
        }

        PageTranslation(
            pageWidth = page.width,
            pageHeight = page.height,
            blocks = translated,
            sourceLanguage = source,
            targetLanguage = target,
            elapsedMillis = System.currentTimeMillis() - started,
        ).also { onProgress(TranslationProgress.Done(it)) }
    }

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
        /** A sentence end, or the dash opus-MT uses to open a new speaker's line. */
        private val SENTENCE_BREAK = Regex("""(?<=[.!?])\s+|\s+-\s+""")

        val DEFAULT_THREADS: Int get() = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)
    }
}

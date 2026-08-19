package yaku.translation.model

/**
 * Language identifiers used across the pipeline. These map onto the codes the bundled
 * translation model was trained with; [modelCode] is what actually gets fed to the tokenizer
 * (NLLB-style models want `jpn_Jpan`, opus-MT style models want `ja`).
 */
enum class TranslationLanguage(val code: String, val modelCode: String, val displayName: String) {
    JAPANESE("ja", "jpn_Jpan", "Japanese"),
    KOREAN("ko", "kor_Hang", "Korean"),
    CHINESE_SIMPLIFIED("zh", "zho_Hans", "Chinese (Simplified)"),
    CHINESE_TRADITIONAL("zh-Hant", "zho_Hant", "Chinese (Traditional)"),
    ENGLISH("en", "eng_Latn", "English"),
    SPANISH("es", "spa_Latn", "Spanish"),
    FRENCH("fr", "fra_Latn", "French"),
    GERMAN("de", "deu_Latn", "German"),
    PORTUGUESE("pt", "por_Latn", "Portuguese"),
    RUSSIAN("ru", "rus_Cyrl", "Russian"),
    ;

    companion object {
        fun fromCode(code: String): TranslationLanguage? = entries.find { it.code == code }
    }
}

/** The result of running the full pipeline over a single page image. */
data class PageTranslation(
    val pageWidth: Int,
    val pageHeight: Int,
    val blocks: List<TextBlock>,
    val sourceLanguage: TranslationLanguage,
    val targetLanguage: TranslationLanguage,
    val elapsedMillis: Long,
) {
    val hasContent: Boolean get() = blocks.any { it.isTranslated }
}

/** Progress reporting so the reader can show something while a page is being worked on. */
sealed interface TranslationProgress {
    data object Idle : TranslationProgress
    data object LoadingModels : TranslationProgress
    data object Detecting : TranslationProgress
    data class Recognizing(val done: Int, val total: Int) : TranslationProgress
    data class Translating(val done: Int, val total: Int) : TranslationProgress
    data class Done(val result: PageTranslation) : TranslationProgress
    data class Failed(val error: Throwable) : TranslationProgress
}

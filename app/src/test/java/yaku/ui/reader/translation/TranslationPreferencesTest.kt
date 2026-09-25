package yaku.ui.reader.translation

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import yaku.core.common.preference.InMemoryPreferenceStore
import yaku.translation.model.TranslationLanguage
import yaku.translation.store.ModelFile
import yaku.translation.store.ModelPack

/**
 * The language settings measured against what a pack can do. Every published pack translates one
 * fixed pair, so a choice it does not offer must not be what reaches the translator.
 */
class TranslationPreferencesTest {

    private val preferences = TranslationPreferences(InMemoryPreferenceStore())

    @Test
    fun `uses the chosen language when the pack offers it`() {
        preferences.sourceLanguage.set("zh")

        preferences.source(pack(sources = listOf("ja", "zh"))) shouldBe TranslationLanguage.CHINESE_SIMPLIFIED
    }

    @Test
    fun `falls back to the pack's own pair when the choice is not offered`() {
        preferences.sourceLanguage.set("ko")
        preferences.targetLanguage.set("es")
        val pack = pack(sources = listOf("zh"), targets = listOf("en"))

        preferences.source(pack) shouldBe TranslationLanguage.CHINESE_SIMPLIFIED
        preferences.target(pack) shouldBe TranslationLanguage.ENGLISH
    }

    @Test
    fun `offers every language when a pack declares none`() {
        preferences.targetLanguage.set("es")

        preferences.target(pack(targets = emptyList())) shouldBe TranslationLanguage.SPANISH
        TranslationPreferences.offered(emptyList()) shouldBe TranslationLanguage.entries
    }

    private fun pack(sources: List<String> = listOf("ja"), targets: List<String> = listOf("en")) = ModelPack(
        id = "test-pack",
        name = "Test pack",
        sourceLanguages = sources,
        targetLanguages = targets,
        detector = file("detector.onnx"),
        recognizerEncoder = file("recognizer_encoder.onnx"),
        recognizerDecoder = file("recognizer_decoder.onnx"),
        recognizerVocab = file("recognizer_vocab.json"),
        translatorEncoder = file("translator_encoder.onnx"),
        translatorDecoder = file("translator_decoder.onnx"),
        translatorVocab = file("translator_vocab.json"),
    )

    private fun file(name: String) = ModelFile(name = name, url = "https://example.com/$name", sha256 = "00")
}

package yaku.translation.store

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import org.junit.jupiter.api.Test

/**
 * A manifest's ids and file names become paths on disk, so anything that could step out of the
 * pack's own directory has to be refused before a byte is written.
 */
class ModelPackValidationTest {

    @Test
    fun `accepts the names the published packs use`() {
        pack(id = "ja-en-base").validationError().shouldBeNull()
        pack(id = "ja-en-comic").validationError().shouldBeNull()
        pack(id = "zh-en-base").validationError().shouldBeNull()
    }

    @Test
    fun `refuses an id that climbs out of the models directory`() {
        pack(id = "../shared_prefs").validationError().shouldNotBeNull()
        pack(id = "..").validationError().shouldNotBeNull()
        pack(id = ".").validationError().shouldNotBeNull()
        pack(id = "").validationError().shouldNotBeNull()
    }

    @Test
    fun `refuses an id with a path separator`() {
        pack(id = "a/b").validationError().shouldNotBeNull()
        pack(id = "a\\b").validationError().shouldNotBeNull()
    }

    @Test
    fun `refuses a file name that climbs out of the pack directory`() {
        pack(detectorName = "../../shared_prefs/prefs.xml").validationError().shouldNotBeNull()
        pack(detectorName = "..").validationError().shouldNotBeNull()
        pack(detectorName = "models/detector.onnx").validationError().shouldNotBeNull()
    }

    @Test
    fun `refuses a file that would overwrite the pack descriptor`() {
        pack(detectorName = ModelRepository.PACK_DESCRIPTOR).validationError().shouldNotBeNull()
    }

    @Test
    fun `refuses two files with the same name`() {
        pack(detectorName = "recognizer_vocab.json").validationError().shouldNotBeNull()
    }

    private fun pack(id: String = "ja-en-base", detectorName: String = "detector.onnx") = ModelPack(
        id = id,
        name = "Test pack",
        detector = file(detectorName),
        recognizerEncoder = file("recognizer_encoder.onnx"),
        recognizerDecoder = file("recognizer_decoder.onnx"),
        recognizerVocab = file("recognizer_vocab.json"),
        translatorEncoder = file("translator_encoder.onnx"),
        translatorDecoder = file("translator_decoder.onnx"),
        translatorVocab = file("translator_vocab.json"),
    )

    private fun file(name: String) = ModelFile(name = name, url = "https://example.com/$name", sha256 = "00")
}

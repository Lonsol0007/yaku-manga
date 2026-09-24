package yaku.translation.store

import android.content.Context
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.MessageDigest

/**
 * Exercises the pack store against a fake network, so what lands on disk can be checked directly.
 *
 * The interceptor answers every request from memory; nothing here opens a socket.
 */
class ModelRepositoryTest {

    @TempDir
    lateinit var filesDir: File

    private val modelsDir get() = File(filesDir, "translation-models")

    // -- paths

    @Test
    fun `refuses to download a pack whose id leaves the models directory`() = runTest {
        val pack = pack(id = "../escaped")

        val outcome = runCatching { repository(bodiesFor(pack)).download(pack).collect() }

        outcome.exceptionOrNull().shouldBeInstanceOf<IllegalArgumentException>()
        File(filesDir, "escaped").exists().shouldBeFalse()
    }

    @Test
    fun `refuses to download a file whose name leaves the pack directory`() = runTest {
        val pack = pack(detectorName = "../escaped.onnx")

        val outcome = runCatching { repository(bodiesFor(pack)).download(pack).collect() }

        outcome.exceptionOrNull().shouldBeInstanceOf<IllegalArgumentException>()
        File(modelsDir, "escaped.onnx").exists().shouldBeFalse()
        File(modelsDir, "escaped.onnx.part").exists().shouldBeFalse()
    }

    @Test
    fun `ignores an installed descriptor that names a different directory`() {
        val dir = File(modelsDir, "ja-en-base").apply { mkdirs() }
        val descriptor = json.encodeToString(ModelPack.serializer(), pack(id = ".."))
        File(dir, ModelRepository.PACK_DESCRIPTOR).writeText(descriptor)

        val repository = repository(emptyMap())

        repository.installedPack("ja-en-base").shouldBeNull()
        repository.installedPacks().shouldBeEmpty()
    }

    // -- fixtures

    private val json = Json { ignoreUnknownKeys = true }

    private fun repository(bodies: Map<String, ByteArray>): ModelRepository {
        val context = mockk<Context> { every { filesDir } returns this@ModelRepositoryTest.filesDir }
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val bytes = bodies.getValue(request.url.pathSegments.last())
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(bytes.toResponseBody())
                    .build()
            }
            .build()
        return ModelRepository(context, client, json)
    }

    /** One megabyte per file, so a pack's worth of bodies is realistic without being slow. */
    private fun contentOf(name: String) = ByteArray(1 shl 20) { (name.hashCode() + it).toByte() }

    /** Keyed by the last segment of each file's URL, which is what the interceptor looks up. */
    private fun bodiesFor(pack: ModelPack): Map<String, ByteArray> =
        pack.files.associate { it.url.substringAfterLast('/') to contentOf(it.name) }

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

    private fun file(name: String) = ModelFile(
        name = name,
        url = "https://example.com/packs/${name.substringAfterLast('/')}",
        sizeBytes = (1 shl 20).toLong(),
        sha256 = sha256(contentOf(name)),
    )

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

package yaku.translation.store

import android.content.Context
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
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

    // -- downloads

    @Test
    fun `a completed download installs every file and the descriptor`() = runTest {
        val pack = pack()
        val repository = repository(bodiesFor(pack))

        repository.download(pack).collect()

        repository.isInstalled(pack).shouldBeTrue()
        repository.installedPack(pack.id)?.id shouldBe pack.id
    }

    @Test
    fun `a checksum mismatch leaves no descriptor and no partial file`() = runTest {
        val pack = pack()
        val bodies = bodiesFor(pack) + ("detector.onnx" to "not the detector".toByteArray())

        val outcome = runCatching { repository(bodies).download(pack).collect() }

        outcome.isFailure.shouldBeTrue()
        File(modelsDir, "${pack.id}/${ModelRepository.PACK_DESCRIPTOR}").exists().shouldBeFalse()
        partFiles(pack).shouldBeEmpty()
    }

    @Test
    fun `a download cancelled part way leaves no descriptor and no partial file`() = runTest {
        val pack = pack()

        // first() takes one progress report and then cancels the download while it is still
        // reading; the body is slow enough that it cannot have finished by then.
        repository(bodiesFor(pack), slow = true).download(pack).first()

        File(modelsDir, "${pack.id}/${ModelRepository.PACK_DESCRIPTOR}").exists().shouldBeFalse()
        partFiles(pack).shouldBeEmpty()
    }

    // -- fixtures

    private val json = Json { ignoreUnknownKeys = true }

    private fun repository(bodies: Map<String, ByteArray>, slow: Boolean = false): ModelRepository {
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
                    .body(if (slow) slowBody(bytes) else bytes.toResponseBody())
                    .build()
            }
            .build()
        return ModelRepository(context, client, json)
    }

    /** A body that trickles out, so a download is still reading when the test cancels it. */
    private fun slowBody(bytes: ByteArray): ResponseBody = object : ResponseBody() {
        private val source = object : ForwardingSource(Buffer().write(bytes)) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                Thread.sleep(20)
                return super.read(sink, byteCount)
            }
        }.buffer()

        override fun contentType(): MediaType? = null
        override fun contentLength(): Long = bytes.size.toLong()
        override fun source(): BufferedSource = source
    }

    private fun partFiles(pack: ModelPack): List<File> =
        File(modelsDir, pack.id).listFiles().orEmpty().filter { it.name.endsWith(".part") }

    /** One megabyte per file: large enough that a slow body takes seconds to deliver. */
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

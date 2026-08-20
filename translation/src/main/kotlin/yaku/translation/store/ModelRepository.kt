package yaku.translation.store

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import logcat.LogPriority
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import okio.sink
import yaku.core.common.util.system.logcat
import java.io.File
import java.security.MessageDigest

/**
 * Owns the on-disk model cache.
 *
 * Everything lives under the app's private files directory, so uninstalling the app removes the
 * weights and no other app can read them. Nothing is fetched unless the user explicitly asks for
 * a pack - the reader never triggers a download on its own.
 */
class ModelRepository(
    private val context: Context,
    private val client: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {

    private val root: File get() = File(context.filesDir, MODELS_DIR).apply { mkdirs() }

    fun packDir(packId: String): File = File(root, packId)

    /**
     * Whether every file in the pack is present and non-empty.
     *
     * Hashes are verified at download time and not re-checked here - re-hashing a few hundred
     * megabytes on every reader open would be far more expensive than the failure it guards
     * against, and a corrupt file is only reachable by editing the directory by hand.
     */
    fun isInstalled(pack: ModelPack): Boolean {
        val dir = packDir(pack.id)
        return pack.files.all { File(dir, it.name).length() > 0L }
    }

    fun installedFile(packId: String, fileName: String): File = File(packDir(packId), fileName)

    /**
     * The descriptor written at download time, or null if the pack was never installed.
     *
     * Reading it back rather than re-fetching the manifest is what lets a downloaded pack work
     * with the device offline, which is the whole point of the feature.
     */
    fun installedPack(packId: String): ModelPack? {
        val descriptor = File(packDir(packId), PACK_DESCRIPTOR)
        if (!descriptor.exists()) return null
        return runCatching { json.decodeFromString<ModelPack>(descriptor.readText()) }.getOrNull()
    }

    /** Every pack currently on disk. */
    fun installedPacks(): List<ModelPack> =
        root.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { installedPack(it.name) }

    /** Total bytes currently occupied by downloaded packs. */
    fun installedBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    suspend fun fetchManifest(manifestUrl: String): ModelManifest = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(manifestUrl).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Manifest request failed: HTTP ${response.code}")
            val body = response.body?.string().orEmpty()
            val manifest = json.decodeFromString<ModelManifest>(body)
            // Stamp the origin onto every pack so an install can tell two same-named packs apart.
            manifest.copy(packs = manifest.packs.map { it.copy(source = manifestUrl) })
        }
    }

    /**
     * Fetches every configured source, keeping whichever ones answer.
     *
     * One unreachable third-party source must not hide the packs the others offer, so failures
     * are collected and returned alongside the results instead of thrown.
     */
    suspend fun fetchAll(sources: Collection<String>): SourceResults = withContext(Dispatchers.IO) {
        val packs = mutableListOf<ModelPack>()
        val failures = mutableMapOf<String, String>()

        for (source in sources.map { it.trim() }.filter { it.isNotEmpty() }.distinct()) {
            runCatching { fetchManifest(source) }
                .onSuccess { packs += it.packs }
                .onFailure { failures[source] = it.message ?: it::class.simpleName.orEmpty() }
        }
        SourceResults(packs = packs, failures = failures)
    }

    data class SourceResults(
        val packs: List<ModelPack>,
        val failures: Map<String, String>,
    )

    /**
     * Downloads every file in [pack] that is not already present and verified.
     *
     * Emits [DownloadProgress] as it goes. Partial downloads are written to a `.part` file and
     * only moved into place once the SHA-256 matches, so an interrupted download can never leave
     * a corrupt model that fails at inference time.
     */
    fun download(pack: ModelPack): Flow<DownloadProgress> = callbackFlow {
        // Packs live in a directory named after their id, so a third-party manifest offering
        // "ja-en-base" would otherwise silently overwrite the installed pack of that name. Refuse
        // instead: replacing someone's working models with an unrelated download, because two
        // authors picked the same string, is not a decision to make on their behalf.
        val existing = installedPack(pack.id)
        if (existing != null && existing.source.isNotEmpty() &&
            pack.source.isNotEmpty() && existing.source != pack.source
        ) {
            close(
                IllegalStateException(
                    "A pack with id '${pack.id}' is already installed from ${existing.source}. " +
                        "Remove it before installing the one from ${pack.source}.",
                ),
            )
            return@callbackFlow
        }

        val dir = packDir(pack.id).apply { mkdirs() }
        val total = pack.totalBytes.coerceAtLeast(1L)
        var completedBytes = 0L

        try {
            for (file in pack.files) {
                val target = File(dir, file.name)
                if (target.exists() && sha256(target).equals(file.sha256, ignoreCase = true)) {
                    completedBytes += target.length()
                    trySend(DownloadProgress(pack.id, file.name, completedBytes, total))
                    continue
                }

                val part = File(dir, file.name + ".part")
                part.delete()

                val request = Request.Builder().url(file.url).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("Download failed for ${file.name}: HTTP ${response.code}")
                    val body = response.body ?: error("Empty body for ${file.name}")

                    part.sink().buffer().use { sink ->
                        val source = body.source()
                        val buffer = okio.Buffer()
                        while (true) {
                            val read = source.read(buffer, DOWNLOAD_CHUNK)
                            if (read == -1L) break
                            sink.write(buffer, read)
                            completedBytes += read
                            trySend(DownloadProgress(pack.id, file.name, completedBytes, total))
                        }
                    }
                }

                val actual = sha256(part)
                if (!actual.equals(file.sha256, ignoreCase = true)) {
                    part.delete()
                    error("Checksum mismatch for ${file.name}: expected ${file.sha256}, got $actual")
                }
                if (!part.renameTo(target)) {
                    part.delete()
                    error("Could not move ${file.name} into place")
                }
            }
            // Record what was installed so the pack can be loaded later with no network access.
            File(dir, PACK_DESCRIPTOR).writeText(json.encodeToString(pack))
            trySend(DownloadProgress(pack.id, null, total, total))
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "Model download failed for ${pack.id}" }
            close(e)
            return@callbackFlow
        }
        close()
    }.flowOn(Dispatchers.IO)

    suspend fun delete(packId: String) = withContext(Dispatchers.IO) {
        packDir(packId).deleteRecursively()
        Unit
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val PACK_DESCRIPTOR = "pack.json"
        private const val MODELS_DIR = "translation-models"
        private const val DOWNLOAD_CHUNK = 64L * 1024L
    }
}

data class DownloadProgress(
    val packId: String,
    /** Null once every file is done. */
    val currentFile: String?,
    val bytesDone: Long,
    val bytesTotal: Long,
) {
    val fraction: Float get() = (bytesDone.toFloat() / bytesTotal.toFloat()).coerceIn(0f, 1f)
}

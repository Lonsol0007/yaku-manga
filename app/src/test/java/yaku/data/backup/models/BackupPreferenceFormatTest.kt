package yaku.data.backup.models

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import org.junit.jupiter.api.Test

/**
 * The names of the preference value types are part of the backup file format.
 *
 * A backup stores each preference value with the name of its type, and those names came from the
 * package the classes sat in until they were pinned. The mirrors below write a preference exactly
 * as another app would, so a rename here fails these tests instead of failing on a phone with
 * "Backup file is corrupted" in front of someone restoring their library.
 */
class BackupPreferenceFormatTest {

    private val parser = ProtoBuf { serializersModule = BackupSerializersModule }

    @Test
    fun `restores a preference written by Mihon`() {
        val written = ProtoBuf.encodeToByteArray(MirrorPreference("pref_theme_mode_key", MihonString("DARK")))

        val restored = parser.decodeFromByteArray<BackupPreference>(written)

        restored.key shouldBe "pref_theme_mode_key"
        restored.value shouldBe StringPreferenceValue("DARK")
    }

    @Test
    fun `restores a preference written by an earlier version of this app`() {
        val written = ProtoBuf.encodeToByteArray(MirrorPreference("relative_time", YakuBoolean(true)))

        val restored = parser.decodeFromByteArray<BackupPreference>(written)

        restored.value shouldBe BooleanPreferenceValue(true)
    }

    @Test
    fun `writes the type names Mihon reads`() {
        val written = parser.encodeToByteArray(BackupPreference("last_used_category", IntPreferenceValue(3)))

        // Latin-1 keeps every byte its own character, so the name is searchable in the payload.
        String(written, Charsets.ISO_8859_1) shouldContain
            "eu.kanade.tachiyomi.data.backup.models.IntPreferenceValue"
    }
}

@Serializable
private data class MirrorPreference(
    @ProtoNumber(1) val key: String,
    @ProtoNumber(2) val value: MirrorValue,
)

@Serializable
private sealed class MirrorValue

@Serializable
@SerialName("eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue")
private data class MihonString(val value: String) : MirrorValue()

@Serializable
@SerialName("yaku.data.backup.models.BooleanPreferenceValue")
private data class YakuBoolean(val value: Boolean) : MirrorValue()

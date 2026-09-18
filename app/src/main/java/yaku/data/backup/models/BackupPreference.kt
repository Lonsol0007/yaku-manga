package yaku.data.backup.models

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.protobuf.ProtoNumber

@Serializable
data class BackupPreference(
    @ProtoNumber(1) val key: String,
    @ProtoNumber(2) val value: PreferenceValue,
)

@Serializable
data class BackupSourcePreferences(
    @ProtoNumber(1) val sourceKey: String,
    @ProtoNumber(2) val prefs: List<BackupPreference>,
)

/**
 * A preference's value, which carries the name of its own type into the backup file.
 *
 * Serialisation writes each subclass's full package name unless it is given one outright, and
 * this app renamed the packages it inherited. A backup written by Mihon therefore arrives naming
 * types that do not exist here, the decoder finds none of them, and the whole file is reported as
 * corrupt - which is what restoring a Mihon backup did, while Mihon restored the same file
 * without complaint.
 *
 * The names below belong to the file format rather than to the code layout, so they are pinned to
 * what Mihon writes and must not follow any later package move.
 */
@Serializable
sealed class PreferenceValue

@Serializable
@SerialName("eu.kanade.tachiyomi.data.backup.models.IntPreferenceValue")
data class IntPreferenceValue(val value: Int) : PreferenceValue()

@Serializable
@SerialName("eu.kanade.tachiyomi.data.backup.models.LongPreferenceValue")
data class LongPreferenceValue(val value: Long) : PreferenceValue()

@Serializable
@SerialName("eu.kanade.tachiyomi.data.backup.models.FloatPreferenceValue")
data class FloatPreferenceValue(val value: Float) : PreferenceValue()

@Serializable
@SerialName("eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue")
data class StringPreferenceValue(val value: String) : PreferenceValue()

@Serializable
@SerialName("eu.kanade.tachiyomi.data.backup.models.BooleanPreferenceValue")
data class BooleanPreferenceValue(val value: Boolean) : PreferenceValue()

@Serializable
@SerialName("eu.kanade.tachiyomi.data.backup.models.StringSetPreferenceValue")
data class StringSetPreferenceValue(val value: Set<String>) : PreferenceValue()

/**
 * Also accepts the type names this app wrote before the ones above were pinned.
 *
 * Versions 0.0.1 and 0.0.2 named the types after this app's own packages. Without this, the
 * release that repairs the format could not read the backups taken with the two releases
 * before it.
 */
val BackupSerializersModule = SerializersModule {
    polymorphicDefaultDeserializer(PreferenceValue::class) { name -> RENAMED_VALUE_TYPES[name] }
}

private val RENAMED_VALUE_TYPES: Map<String, DeserializationStrategy<PreferenceValue>> = mapOf(
    "yaku.data.backup.models.IntPreferenceValue" to IntPreferenceValue.serializer(),
    "yaku.data.backup.models.LongPreferenceValue" to LongPreferenceValue.serializer(),
    "yaku.data.backup.models.FloatPreferenceValue" to FloatPreferenceValue.serializer(),
    "yaku.data.backup.models.StringPreferenceValue" to StringPreferenceValue.serializer(),
    "yaku.data.backup.models.BooleanPreferenceValue" to BooleanPreferenceValue.serializer(),
    "yaku.data.backup.models.StringSetPreferenceValue" to StringSetPreferenceValue.serializer(),
)

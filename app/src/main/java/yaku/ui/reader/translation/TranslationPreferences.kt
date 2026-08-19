package yaku.ui.reader.translation

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import yaku.core.common.preference.Preference
import yaku.core.common.preference.PreferenceStore
import yaku.translation.model.TranslationLanguage

@Inject
@SingleIn(AppScope::class)
class TranslationPreferences(
    preferenceStore: PreferenceStore,
) {

    /** Master switch. Off by default - translation costs battery and the models are not present. */
    val enabled: Preference<Boolean> = preferenceStore.getBoolean("pref_translation_enabled", false)

    val sourceLanguage: Preference<String> = preferenceStore.getString(
        "pref_translation_source_language",
        TranslationLanguage.JAPANESE.code,
    )

    val targetLanguage: Preference<String> = preferenceStore.getString(
        "pref_translation_target_language",
        TranslationLanguage.ENGLISH.code,
    )

    /** Id of the downloaded [yaku.translation.store.ModelPack] to run. Empty means none chosen. */
    val activePackId: Preference<String> = preferenceStore.getString("pref_translation_pack_id", "")

    /**
     * Where to look for downloadable model packs.
     *
     * Empty by default: the app makes no network request for models unless the user points it at
     * a manifest, so a user who sideloads packs into the app's files directory never talks to a
     * server at all.
     */
    val manifestUrl: Preference<String> = preferenceStore.getString("pref_translation_manifest_url", "")

    val wifiOnlyDownloads: Preference<Boolean> =
        preferenceStore.getBoolean("pref_translation_wifi_only", true)

    /** Keep the last N translated pages in memory so flipping back is instant. */
    val pageCacheSize: Preference<Int> = preferenceStore.getInt("pref_translation_page_cache", 4)

    fun source(): TranslationLanguage =
        TranslationLanguage.fromCode(sourceLanguage.get()) ?: TranslationLanguage.JAPANESE

    fun target(): TranslationLanguage =
        TranslationLanguage.fromCode(targetLanguage.get()) ?: TranslationLanguage.ENGLISH
}

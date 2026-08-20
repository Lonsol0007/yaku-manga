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
     * Manifests to look in for downloadable packs.
     *
     * The official source is pre-filled so translation is usable without hunting for a URL, but
     * nothing is fetched until the pack browser is opened or a download is started - launching
     * the app still makes no request for models. Clearing the list, or sideloading packs into the
     * files directory by hand, leaves the feature entirely offline.
     *
     * A set rather than one URL so third-party packs are a first-class case, instead of something
     * you reach by overwriting the official source and losing it.
     */
    val packSources: Preference<Set<String>> = preferenceStore.getStringSet(
        "pref_translation_pack_sources",
        setOf(DEFAULT_PACK_SOURCE),
    )

    val wifiOnlyDownloads: Preference<Boolean> =
        preferenceStore.getBoolean("pref_translation_wifi_only", true)

    /** Keep the last N translated pages in memory so flipping back is instant. */
    val pageCacheSize: Preference<Int> = preferenceStore.getInt("pref_translation_page_cache", 4)

    fun source(): TranslationLanguage =
        TranslationLanguage.fromCode(sourceLanguage.get()) ?: TranslationLanguage.JAPANESE

    fun target(): TranslationLanguage =
        TranslationLanguage.fromCode(targetLanguage.get()) ?: TranslationLanguage.ENGLISH

    companion object {
        /**
         * Packs published alongside the app's own releases.
         *
         * A release tag rather than a branch path: the manifest records a SHA-256 per file, so it
         * has to stay pinned to the exact files it was generated against. A moving target would
         * start failing checksums the moment the packs were rebuilt.
         */
        const val DEFAULT_PACK_SOURCE =
            "https://github.com/Lonsol0007/yaku-manga/releases/download/packs-v1/manifest.json"
    }
}

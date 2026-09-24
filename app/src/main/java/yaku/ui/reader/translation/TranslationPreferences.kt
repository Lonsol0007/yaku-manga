package yaku.ui.reader.translation

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import yaku.core.common.preference.Preference
import yaku.core.common.preference.PreferenceStore
import yaku.translation.model.TranslationLanguage
import yaku.translation.store.ModelPack

@Inject
@SingleIn(AppScope::class)
class TranslationPreferences(
    preferenceStore: PreferenceStore,
) {

    /** Master switch. Off by default - translation costs battery and the models are not present. */
    val enabled: Preference<Boolean> = preferenceStore.getBoolean("pref_translation_enabled", false)

    /**
     * Shows the tab that translates a pasted link. Off by default.
     *
     * It depends on guessing which images on an arbitrary page are manga, which works on some
     * sites and returns a gallery of thumbnails on others. That is a reasonable thing to
     * experiment with and a poor thing to meet without warning in the navigation bar.
     */
    val linkTranslateEnabled: Preference<Boolean> =
        preferenceStore.getBoolean("pref_translation_link_tab_enabled", false)

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
        DEFAULT_PACK_SOURCES,
    )

    val wifiOnlyDownloads: Preference<Boolean> =
        preferenceStore.getBoolean("pref_translation_wifi_only", true)

    /** Keep the last N translated pages in memory so flipping back is instant. */
    val pageCacheSize: Preference<Int> = preferenceStore.getInt("pref_translation_page_cache", 4)

    /** The source language to translate [pack] from: the one chosen if it offers it. See [offered]. */
    fun source(pack: ModelPack): TranslationLanguage = resolve(sourceLanguage.get(), pack.sourceLanguages)

    /** The target language to translate [pack] into: the one chosen if it offers it. See [offered]. */
    fun target(pack: ModelPack): TranslationLanguage = resolve(targetLanguage.get(), pack.targetLanguages)

    companion object {
        /**
         * The languages a pack offers: the ones it declares, or every language when it declares none.
         *
         * Every published pack translates one fixed pair, so a choice outside what the pack
         * declares was never a choice - the pack translated its own pair regardless. A choice it
         * does not offer gives way to the first language it does.
         */
        fun offered(declared: List<String>): List<TranslationLanguage> =
            declared.mapNotNull(TranslationLanguage::fromCode).ifEmpty { TranslationLanguage.entries }

        private fun resolve(chosen: String, declared: List<String>): TranslationLanguage {
            val offered = offered(declared)
            return offered.firstOrNull { it.code == chosen } ?: offered.first()
        }

        /**
         * Packs published alongside the app's own releases.
         *
         * A release tag rather than a branch path: the manifest records a SHA-256 per file, so it
         * has to stay pinned to the exact files it was generated against. A moving target would
         * start failing checksums the moment the packs were rebuilt.
         */
        const val PACK_SOURCE_V1 =
            "https://github.com/Lonsol0007/yaku-manga/releases/download/packs-v1/manifest.json"

        /**
         * Packs that need this version of the app or a later one.
         *
         * Earlier versions read only [PACK_SOURCE_V1] and skip settings they do not recognise, so a
         * pack listed there that depends on a newer one - a comic detector - would be offered to
         * them and then fail on every page. A manifest they never read is the only way to keep
         * such a pack from them.
         */
        const val PACK_SOURCE_V2 =
            "https://github.com/Lonsol0007/yaku-manga/releases/download/packs-v2/manifest.json"

        val DEFAULT_PACK_SOURCES = setOf(PACK_SOURCE_V1, PACK_SOURCE_V2)
    }
}

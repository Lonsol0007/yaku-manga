package eu.kanade.tachiyomi.source

import android.content.Context
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import yaku.app.di.appGraph
import yaku.domain.source.model.StubSource
import yaku.source.local.isLocal

fun Source.getNameForMangaInfo(): String {
    val preferences = Injekt.get<Context>().appGraph.sourcePreferences
    val enabledLanguages = preferences.enabledLanguages.get()
        .filterNot { it in listOf("all", "other") }
    val hasOneActiveLanguages = enabledLanguages.size == 1
    val isInEnabledLanguages = lang in enabledLanguages
    return when {
        // For edge cases where user disables a source they got manga of in their library.
        hasOneActiveLanguages && !isInEnabledLanguages -> toString()
        // Hide the language tag when only one language is used.
        hasOneActiveLanguages && isInEnabledLanguages -> name
        else -> toString()
    }
}

fun Source.isLocalOrStub(): Boolean = isLocal() || this is StubSource

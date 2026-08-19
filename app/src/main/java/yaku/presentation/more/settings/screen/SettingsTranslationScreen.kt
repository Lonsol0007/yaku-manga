package yaku.presentation.more.settings.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import yaku.app.di.appGraph
import yaku.i18n.MR
import yaku.presentation.core.i18n.stringResource
import yaku.presentation.more.settings.Preference
import yaku.translation.model.TranslationLanguage
import yaku.translation.store.ModelPack
import yaku.ui.reader.translation.PageTranslator

object SettingsTranslationScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.pref_category_translation

    @Composable
    override fun getPreferences(): List<Preference> {
        val context = LocalContext.current
        val graph = remember { context.appGraph }
        val prefs = remember { graph.translationPreferences }
        val translator = remember { graph.pageTranslator }

        val installed = remember { translator.repository.installedPacks() }
        val noPacksLabel = stringResource(MR.strings.pref_translation_no_packs)
        val packEntries = remember(installed, noPacksLabel) {
            if (installed.isEmpty()) {
                mapOf("" to noPacksLabel)
            } else {
                installed.associate { it.id to it.name }
            }
        }

        return listOf(
            Preference.PreferenceItem.InfoPreference(
                title = stringResource(MR.strings.pref_translation_info),
            ),
            Preference.PreferenceItem.SwitchPreference(
                preference = prefs.enabled,
                title = stringResource(MR.strings.pref_translation_enabled),
                subtitle = stringResource(MR.strings.pref_translation_enabled_summary),
                enabled = installed.isNotEmpty(),
            ),
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.pref_translation_languages),
                preferenceItems = listOf(
                    Preference.PreferenceItem.ListPreference(
                        preference = prefs.sourceLanguage,
                        entries = TranslationLanguage.entries.associate { it.code to it.displayName },
                        title = stringResource(MR.strings.pref_translation_source_language),
                    ),
                    Preference.PreferenceItem.ListPreference(
                        preference = prefs.targetLanguage,
                        entries = TranslationLanguage.entries.associate { it.code to it.displayName },
                        title = stringResource(MR.strings.pref_translation_target_language),
                    ),
                ),
            ),
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.pref_translation_models),
                preferenceItems = listOf(
                    Preference.PreferenceItem.BasicListPreference(
                        value = prefs.activePackId.get(),
                        entries = packEntries,
                        title = stringResource(MR.strings.pref_translation_active_pack),
                        onValueChanged = {
                            prefs.activePackId.set(it)
                            // Drop the loaded sessions so the next page picks up the new pack.
                            translator.release()
                        },
                    ),
                    Preference.PreferenceItem.EditTextPreference(
                        preference = prefs.manifestUrl,
                        title = stringResource(MR.strings.pref_translation_manifest_url),
                        subtitle = stringResource(MR.strings.pref_translation_manifest_url_summary),
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = prefs.wifiOnlyDownloads,
                        title = stringResource(MR.strings.pref_translation_wifi_only),
                    ),
                    Preference.PreferenceItem.CustomPreference(
                        title = stringResource(MR.strings.pref_translation_browse_packs),
                    ) {
                        PackDownloader(translator, prefs.manifestUrl.get())
                    },
                ),
            ),
        )
    }
}

/**
 * Minimal pack browser: fetch the manifest on demand, list what it offers, download one.
 *
 * Deliberately does nothing until the user taps - entering a manifest URL should not by itself
 * cause a request.
 */
@Composable
private fun PackDownloader(translator: PageTranslator, manifestUrl: String) {
    val scope = rememberCoroutineScope()
    var packs by remember { mutableStateOf<List<ModelPack>>(emptyList()) }
    var showDialog by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<Float?>(null) }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        OutlinedButton(
            onClick = {
                if (manifestUrl.isBlank()) {
                    status = "Set a manifest URL first"
                    return@OutlinedButton
                }
                scope.launch {
                    status = "Fetching manifest…"
                    runCatching { translator.repository.fetchManifest(manifestUrl) }
                        .onSuccess {
                            packs = it.packs
                            status = null
                            showDialog = true
                        }
                        .onFailure { status = it.message ?: "Could not read the manifest" }
                }
            },
            enabled = progress == null,
        ) {
            Text(stringResource(MR.strings.pref_translation_browse_packs))
        }

        progress?.let {
            LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        }
        status?.let { Text(it, modifier = Modifier.padding(top = 8.dp)) }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) { Text(stringResource(MR.strings.action_cancel)) }
            },
            title = { Text(stringResource(MR.strings.pref_translation_available_packs)) },
            text = {
                LazyColumn {
                    items(packs) { pack ->
                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text(pack.name)
                            Text("${pack.totalBytes / 1_000_000} MB — ${pack.description}")
                            Button(onClick = {
                                showDialog = false
                                scope.launch {
                                    progress = 0f
                                    translator.repository.download(pack)
                                        .catch {
                                            status = it.message ?: "Download failed"
                                            progress = null
                                        }
                                        .collect { progress = it.fraction }
                                    progress = null
                                    status = "Downloaded ${pack.name}"
                                }
                            }) {
                                Text(stringResource(MR.strings.action_download))
                            }
                        }
                    }
                }
            },
        )
    }

    LaunchedEffect(manifestUrl) { status = null }
}

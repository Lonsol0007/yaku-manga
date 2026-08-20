package yaku.presentation.more.settings.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
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
import yaku.ui.reader.translation.TranslationPreferences

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
                    Preference.PreferenceItem.SwitchPreference(
                        preference = prefs.wifiOnlyDownloads,
                        title = stringResource(MR.strings.pref_translation_wifi_only),
                    ),
                    Preference.PreferenceItem.CustomPreference(
                        title = stringResource(MR.strings.pref_translation_browse_packs),
                    ) {
                        PackDownloader(translator, prefs)
                    },
                ),
            ),
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.pref_translation_sources),
                preferenceItems = listOf(
                    Preference.PreferenceItem.InfoPreference(
                        title = stringResource(MR.strings.pref_translation_sources_warning),
                    ),
                    Preference.PreferenceItem.CustomPreference(
                        title = stringResource(MR.strings.pref_translation_sources),
                    ) {
                        PackSources(prefs)
                    },
                ),
            ),
        )
    }
}

/**
 * Add and remove pack manifests.
 *
 * The official source is a default, not a fixture: it can be removed like any other. Someone who
 * wants only their own packs, or no network source at all, is entitled to that.
 */
@Composable
private fun PackSources(prefs: TranslationPreferences) {
    var sources by remember { mutableStateOf(prefs.packSources.get()) }
    var showAdd by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    fun commit(updated: Set<String>) {
        sources = updated
        prefs.packSources.set(updated)
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (sources.isEmpty()) {
            Text(
                text = stringResource(MR.strings.pref_translation_no_sources),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        sources.sorted().forEach { source ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = source,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { commit(sources - source) }) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = stringResource(MR.strings.action_delete),
                    )
                }
            }
        }

        OutlinedButton(
            onClick = {
                draft = ""
                error = null
                showAdd = true
            },
        ) {
            Text(stringResource(MR.strings.pref_translation_add_source))
        }
    }

    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text(stringResource(MR.strings.pref_translation_add_source)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = {
                            draft = it
                            error = null
                        },
                        singleLine = true,
                        label = { Text(stringResource(MR.strings.pref_translation_manifest_url)) },
                    )
                    error?.let {
                        Text(text = it, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val candidate = draft.trim()
                        // Validated here rather than at fetch time, so a typo is caught while the
                        // user is still looking at the field they typed it into.
                        if (!candidate.startsWith("http://") && !candidate.startsWith("https://")) {
                            error = "Must start with http:// or https://"
                        } else {
                            commit(sources + candidate)
                            showAdd = false
                        }
                    },
                ) {
                    Text(stringResource(MR.strings.action_add))
                }
            },
            dismissButton = {
                TextButton(onClick = { showAdd = false }) {
                    Text(stringResource(MR.strings.action_cancel))
                }
            },
        )
    }
}

/**
 * Fetch every configured source, list what they offer, download one.
 *
 * Deliberately does nothing until the user taps. Having a source configured must not by itself
 * cause a request, or shipping a default source would turn every launch into a network call.
 */
@Composable
private fun PackDownloader(translator: PageTranslator, prefs: TranslationPreferences) {
    val scope = rememberCoroutineScope()
    var packs by remember { mutableStateOf<List<ModelPack>>(emptyList()) }
    var failures by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var showDialog by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<Float?>(null) }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        OutlinedButton(
            onClick = {
                val sources = prefs.packSources.get()
                if (sources.isEmpty()) {
                    status = "Add a pack source first"
                    return@OutlinedButton
                }
                scope.launch {
                    status = "Checking ${sources.size} source(s)…"
                    val results = translator.repository.fetchAll(sources)
                    packs = results.packs
                    failures = results.failures
                    status = when {
                        results.packs.isNotEmpty() -> null
                        results.failures.isNotEmpty() -> results.failures.values.first()
                        else -> "No packs offered"
                    }
                    if (results.packs.isNotEmpty()) showDialog = true
                }
            },
            enabled = progress == null,
        ) {
            Text(stringResource(MR.strings.pref_translation_browse_packs))
        }

        progress?.let {
            LinearProgressIndicator(
                progress = { it },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
        status?.let { Text(it, modifier = Modifier.padding(top = 8.dp)) }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text(stringResource(MR.strings.action_cancel))
                }
            },
            title = { Text(stringResource(MR.strings.pref_translation_available_packs)) },
            text = {
                LazyColumn {
                    // A source that failed is reported rather than silently omitted; otherwise a
                    // typo in a URL looks identical to a source with nothing to offer.
                    items(failures.entries.toList()) { (source, reason) ->
                        Text(
                            text = "$source — $reason",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                    items(packs) { pack ->
                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text(pack.name)
                            Text(
                                text = "${pack.totalBytes / 1_000_000} MB — ${pack.description}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            // Which source offered it, so two packs of the same name are
                            // distinguishable before one is downloaded over the other.
                            Text(
                                text = pack.source,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Button(
                                onClick = {
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
                                },
                            ) {
                                Text(stringResource(MR.strings.action_download))
                            }
                        }
                    }
                }
            },
        )
    }

    LaunchedEffect(Unit) { status = null }
}

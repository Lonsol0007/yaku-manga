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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.util.system.activeNetworkState
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

        // Bumped when a download finishes. Without it the installed list is read once and a
        // freshly downloaded pack stays unselectable until Settings is left and reopened.
        var installedToken by remember { mutableIntStateOf(0) }
        val installed = remember(installedToken) { translator.repository.installedPacks() }
        val noPacksLabel = stringResource(MR.strings.pref_translation_no_packs)
        val noneSelectedLabel = stringResource(MR.strings.pref_translation_no_pack_selected)
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
                        // The default subtitle is `subtitle.format(entries[value])`, which prints
                        // the literal string "null" whenever the stored id is not one of the
                        // entries - as it is with no pack selected, or after the selected pack is
                        // deleted. Say what is actually true instead.
                        subtitleProvider = { value, entries ->
                            entries[value] ?: noneSelectedLabel
                        },
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
                        PackDownloader(translator, prefs, onInstalled = { installedToken++ })
                    },
                    Preference.PreferenceItem.CustomPreference(
                        title = stringResource(MR.strings.pref_translation_installed_packs),
                    ) {
                        InstalledPacks(
                            translator = translator,
                            prefs = prefs,
                            installed = installed,
                            onChanged = { installedToken++ },
                        )
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
private fun PackDownloader(
    translator: PageTranslator,
    prefs: TranslationPreferences,
    onInstalled: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val wifiRequiredMessage = stringResource(MR.strings.pref_translation_wifi_required)
    val failedLabel = stringResource(MR.strings.pref_translation_download_failed)
    val incompleteLabel = stringResource(MR.strings.pref_translation_download_incomplete)
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
                    status = null
                    // Open the dialog whether or not anything was found. Reporting a total
                    // failure through a single line of status text under the button is
                    // indistinguishable from the button doing nothing at all, which is exactly
                    // how it was described when it broke.
                    showDialog = true
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
                    if (packs.isEmpty() && failures.isEmpty()) {
                        item {
                            Text(
                                text = stringResource(MR.strings.pref_translation_sources_empty),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
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
                                    // Honour the Wi-Fi switch at the point the bytes would
                                    // actually move. A pack is a few hundred megabytes, so
                                    // spending that on mobile data while the setting says
                                    // otherwise is a real cost, not a cosmetic slip. Checked
                                    // here rather than at browse time because reading a
                                    // manifest is a few kilobytes and worth allowing.
                                    if (prefs.wifiOnlyDownloads.get() &&
                                        !context.activeNetworkState().isWifi
                                    ) {
                                        status = wifiRequiredMessage
                                        return@Button
                                    }
                                    scope.launch {
                                        progress = 0f
                                        // Deliberately not Flow.catch: it handles the error and
                                        // lets the flow complete *normally*, so everything after
                                        // it ran on failure too - reporting "Downloaded" for a
                                        // download that never happened and selecting a pack that
                                        // was never installed.
                                        val outcome = runCatching {
                                            translator.repository.download(pack)
                                                .collect { progress = it.fraction }
                                        }
                                        progress = null

                                        // Confirm against disk rather than trusting the flow to
                                        // have finished its work. That is what "Downloaded"
                                        // should mean, and it is cheap to verify.
                                        val landed = translator.repository.installedPack(pack.id)

                                        status = when {
                                            outcome.isFailure ->
                                                outcome.exceptionOrNull()?.message ?: failedLabel
                                            landed == null -> incompleteLabel
                                            else -> {
                                                // Adopt the new pack when nothing valid is
                                                // selected; a deliberate choice is left alone.
                                                val current = prefs.activePackId.get()
                                                val valid = translator.repository
                                                    .installedPacks().any { it.id == current }
                                                if (!valid) {
                                                    prefs.activePackId.set(pack.id)
                                                    translator.release()
                                                }
                                                "Downloaded ${pack.name}"
                                            }
                                        }
                                        // Re-read the installed list so the pack is selectable
                                        // and the master switch enables without leaving Settings.
                                        onInstalled()
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

/**
 * Lists what is on disk and lets it be removed.
 *
 * Packs are a few hundred megabytes each and the app cannot reclaim that on its own, so removal
 * has to be reachable from the same screen that installs them.
 */
@Composable
private fun InstalledPacks(
    translator: PageTranslator,
    prefs: TranslationPreferences,
    installed: List<ModelPack>,
    onChanged: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var pendingDelete by remember { mutableStateOf<ModelPack?>(null) }
    // Resolved up here: buildString below is not a composable scope.
    val activeSuffix = stringResource(MR.strings.pref_translation_pack_active)

    if (installed.isEmpty()) {
        Text(
            text = stringResource(MR.strings.pref_translation_no_packs),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        return
    }

    val activeId = prefs.activePackId.get()

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        installed.forEach { pack ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = pack.name, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = buildString {
                            append("${pack.totalBytes / 1_000_000} MB")
                            if (pack.id == activeId) {
                                append(" — ")
                                append(activeSuffix)
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { pendingDelete = pack }) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = stringResource(MR.strings.action_delete),
                    )
                }
            }
        }
    }

    pendingDelete?.let { pack ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(MR.strings.pref_translation_delete_pack)) },
            text = {
                Text(
                    stringResource(
                        MR.strings.pref_translation_delete_pack_confirm,
                        pack.name,
                        pack.totalBytes / 1_000_000,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    scope.launch {
                        // Release before deleting: the engine may hold open handles to these
                        // files, and closing an OrtSession after its weights vanish is a native
                        // failure rather than a catchable one.
                        if (pack.id == prefs.activePackId.get()) {
                            translator.release()
                            prefs.activePackId.set("")
                        }
                        translator.repository.delete(pack.id)
                        onChanged()
                    }
                }) {
                    Text(stringResource(MR.strings.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(MR.strings.action_cancel))
                }
            },
        )
    }
}

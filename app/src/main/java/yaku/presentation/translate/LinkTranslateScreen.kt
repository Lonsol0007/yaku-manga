package yaku.presentation.translate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import yaku.i18n.MR
import yaku.presentation.components.AppBar
import yaku.presentation.core.components.material.Scaffold
import yaku.presentation.core.i18n.stringResource
import yaku.ui.translate.LinkTranslateViewModel
import yaku.ui.translate.LinkTranslateViewModel.Error
import yaku.ui.translate.LinkTranslateViewModel.Stage

@Composable
fun LinkTranslateScreen(
    state: LinkTranslateViewModel.State,
    onUrlChange: (String) -> Unit,
    onTranslate: () -> Unit,
    onCancel: () -> Unit,
) {
    Scaffold(
        topBar = { scrollBehavior ->
            AppBar(
                title = stringResource(MR.strings.label_translate_link),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = contentPadding.calculateTopPadding() + 12.dp,
                bottom = contentPadding.calculateBottomPadding() + 12.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = state.url,
                        onValueChange = onUrlChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(MR.strings.translate_link_field)) },
                        supportingText = { Text(stringResource(MR.strings.translate_link_hint)) },
                        singleLine = true,
                        enabled = !state.isWorking,
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onTranslate,
                            enabled = state.url.isNotBlank() && !state.isWorking,
                        ) {
                            Text(stringResource(MR.strings.action_translate))
                        }
                        if (state.isWorking) {
                            OutlinedButton(onClick = onCancel) {
                                Text(stringResource(MR.strings.action_cancel))
                            }
                        }
                    }

                    Progress(state.stage)
                    state.error?.let { ErrorMessage(it) }
                }
            }

            items(state.pages, key = { it.file.path }) { page ->
                Card(modifier = Modifier.padding(horizontal = 16.dp)) {
                    AsyncImage(
                        model = page.file,
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = ContentScale.FillWidth,
                    )
                    Text(
                        text = page.source,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Progress(stage: Stage) {
    when (stage) {
        Stage.Idle -> Unit
        Stage.Fetching -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(MR.strings.translate_link_fetching))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        is Stage.Translating -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(MR.strings.translate_link_translating))
                Text("${stage.done} / ${stage.total}")
            }
            LinearProgressIndicator(
                progress = { if (stage.total == 0) 0f else stage.done.toFloat() / stage.total },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ErrorMessage(error: Error) {
    val message = when (error) {
        Error.BadUrl -> stringResource(MR.strings.translate_link_error_bad_url)
        Error.NoImages -> stringResource(MR.strings.translate_link_error_no_images)
        Error.AllFailed -> stringResource(MR.strings.translate_link_error_all_failed)
        Error.TranslationOff -> stringResource(MR.strings.translate_link_error_disabled)
        is Error.UnsupportedType ->
            stringResource(MR.strings.translate_link_error_unsupported, error.contentType)
        is Error.Http -> stringResource(MR.strings.translate_link_error_http, error.code)
        is Error.Unreachable -> stringResource(MR.strings.translate_link_error_unreachable, error.reason)
    }
    Text(text = message, color = MaterialTheme.colorScheme.error)
}

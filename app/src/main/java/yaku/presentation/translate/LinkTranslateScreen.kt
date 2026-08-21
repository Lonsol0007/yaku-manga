package yaku.presentation.translate

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
            item(key = "controls") {
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
                        // Cancel exists only while a run is in flight, so it grows in beside the
                        // primary button rather than popping into place.
                        AnimatedVisibility(
                            visible = state.isWorking,
                            enter = fadeIn() + expandHorizontally(),
                            exit = fadeOut() + shrinkHorizontally(),
                        ) {
                            OutlinedButton(onClick = onCancel) {
                                Text(stringResource(MR.strings.action_cancel))
                            }
                        }
                    }

                    AnimatedVisibility(
                        visible = state.isWorking,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically(),
                    ) {
                        Progress(state.stage)
                    }

                    ErrorMessage(state.error)
                }
            }

            items(state.pages, key = { it.file.path }) { page ->
                Card(
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        // Pages land one at a time over many seconds. Without this they snap in
                        // and shove whatever is below them down mid-read.
                        .animateItem(
                            fadeInSpec = spring(stiffness = 200f),
                            placementSpec = spring(stiffness = 200f),
                        ),
                ) {
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
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val translating = stage as? Stage.Translating

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(
                    if (translating == null) {
                        MR.strings.translate_link_fetching
                    } else {
                        MR.strings.translate_link_translating
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            AnimatedVisibility(visible = translating != null, enter = fadeIn(), exit = fadeOut()) {
                Text(
                    text = translating?.let { "${it.done} / ${it.total}" }.orEmpty(),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }

        if (translating == null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            // Animated rather than stepped: a page takes seconds, and a bar that jumps once per
            // page and then sits still reads as stalled.
            val target = if (translating.total == 0) {
                0f
            } else {
                translating.done.toFloat() / translating.total
            }
            val progress by animateFloatAsState(targetValue = target, label = "translateProgress")
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ErrorMessage(error: Error?) {
    // Keep the last error after it clears, so the text stays put while the row collapses instead
    // of blanking on the first frame of the exit animation. Keying remember on `error` would do
    // the opposite - it would drop the message precisely when it is still needed.
    var remembered by remember { mutableStateOf<Error?>(null) }
    // Assigned from SideEffect, not straight from the composable body. Writing snapshot state
    // during composition that the same composition reads invalidates the scope that just ran,
    // which is a recomposition loop rather than a one-off update.
    SideEffect { if (error != null) remembered = error }
    AnimatedVisibility(
        visible = error != null,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        remembered?.let {
            Text(
                text = messageFor(it),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun messageFor(error: Error): String = when (error) {
    Error.BadUrl -> stringResource(MR.strings.translate_link_error_bad_url)
    is Error.NoImages -> error.detail
    Error.AllFailed -> stringResource(MR.strings.translate_link_error_all_failed)
    Error.TranslationOff -> stringResource(MR.strings.translate_link_error_disabled)
    is Error.UnsupportedType ->
        stringResource(MR.strings.translate_link_error_unsupported, error.contentType)
    is Error.Http -> stringResource(MR.strings.translate_link_error_http, error.code)
    is Error.Unreachable -> stringResource(MR.strings.translate_link_error_unreachable, error.reason)
}

package yaku.ui.translate

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.tab.TabOptions
import dev.zacsweers.metrox.viewmodel.metroViewModel
import yaku.i18n.MR
import yaku.presentation.core.i18n.stringResource
import yaku.presentation.translate.LinkTranslateScreen
import yaku.presentation.util.Tab

data object LinkTranslateTab : Tab {

    override val options: TabOptions
        @Composable
        get() = TabOptions(
            index = 4u,
            title = stringResource(MR.strings.label_translate_link),
            icon = rememberVectorPainter(Icons.Outlined.Translate),
        )

    @Composable
    override fun Content() {
        val viewModel = metroViewModel<LinkTranslateViewModel>()
        val state by viewModel.state.collectAsStateWithLifecycle()

        LinkTranslateScreen(
            state = state,
            onUrlChange = viewModel::updateUrl,
            onTranslate = viewModel::translate,
            onCancel = viewModel::cancel,
            onClear = viewModel::clear,
            onSave = viewModel::save,
        )
    }
}

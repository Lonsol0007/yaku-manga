package yaku.presentation.track.components

import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import yaku.data.track.Tracker
import yaku.test.DummyTracker

internal class TrackLogoIconPreviewProvider : PreviewParameterProvider<Tracker> {

    override val values: Sequence<Tracker>
        get() = sequenceOf(
            DummyTracker(
                id = 1L,
                name = "Dummy Tracker",
            ),
        )
}

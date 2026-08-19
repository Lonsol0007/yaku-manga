package yaku.ui.reader.setting

import androidx.annotation.DrawableRes
import dev.icerock.moko.resources.StringResource
import yaku.R
import yaku.app.di.appGraph
import yaku.i18n.MR
import yaku.ui.reader.ReaderActivity
import yaku.ui.reader.viewer.Viewer
import yaku.ui.reader.viewer.pager.L2RPagerViewer
import yaku.ui.reader.viewer.pager.R2LPagerViewer
import yaku.ui.reader.viewer.pager.VerticalPagerViewer
import yaku.ui.reader.viewer.webgpu.WebGpuViewer
import yaku.ui.reader.viewer.webgpu.WebGpuViewerContinuous
import yaku.ui.reader.viewer.webtoon.WebtoonViewer

enum class ReadingMode(
    val stringRes: StringResource,
    @DrawableRes val iconRes: Int,
    val flagValue: Int,
    val direction: Direction? = null,
    val type: ViewerType? = null,
) {
    DEFAULT(MR.strings.label_default, R.drawable.ic_reader_default_24dp, 0x00000000),
    LEFT_TO_RIGHT(
        MR.strings.left_to_right_viewer,
        R.drawable.ic_reader_ltr_24dp,
        0x00000001,
        Direction.Horizontal,
        ViewerType.Pager,
    ),
    RIGHT_TO_LEFT(
        MR.strings.right_to_left_viewer,
        R.drawable.ic_reader_rtl_24dp,
        0x00000002,
        Direction.Horizontal,
        ViewerType.Pager,
    ),
    VERTICAL(
        MR.strings.vertical_viewer,
        R.drawable.ic_reader_vertical_24dp,
        0x00000003,
        Direction.Vertical,
        ViewerType.Pager,
    ),
    WEBTOON(
        MR.strings.webtoon_viewer,
        R.drawable.ic_reader_webtoon_24dp,
        0x00000004,
        Direction.Vertical,
        ViewerType.Webtoon,
    ),
    CONTINUOUS_VERTICAL(
        MR.strings.vertical_plus_viewer,
        R.drawable.ic_reader_continuous_vertical_24dp,
        0x00000005,
        Direction.Vertical,
        ViewerType.Webtoon,
    ),
    ;

    companion object {
        const val MASK = 0x00000007

        fun fromPreference(preference: Int?): ReadingMode = entries.find { it.flagValue == preference } ?: DEFAULT

        fun isPagerType(preference: Int): Boolean {
            val mode = fromPreference(preference)
            return mode.type is ViewerType.Pager
        }

        fun toViewer(preference: Int?, activity: ReaderActivity): Viewer {
            if (activity.appGraph.basePreferences.highQualityRenderer.get()) {
                return when (fromPreference(preference)) {
                    LEFT_TO_RIGHT -> WebGpuViewer(activity, isReversed = false, isVertical = false)
                    RIGHT_TO_LEFT -> WebGpuViewer(activity, isReversed = true, isVertical = false)
                    VERTICAL -> WebGpuViewer(activity, isReversed = false, isVertical = true)
                    WEBTOON -> WebGpuViewerContinuous(activity)
                    CONTINUOUS_VERTICAL -> WebGpuViewerContinuous(activity)
                    DEFAULT -> throw IllegalStateException("Preference value must be resolved: $preference")
                }
            }
            return when (fromPreference(preference)) {
                LEFT_TO_RIGHT -> L2RPagerViewer(activity)
                RIGHT_TO_LEFT -> R2LPagerViewer(activity)
                VERTICAL -> VerticalPagerViewer(activity)
                WEBTOON -> WebtoonViewer(activity)
                CONTINUOUS_VERTICAL -> WebtoonViewer(activity, isContinuous = false)
                DEFAULT -> throw IllegalStateException("Preference value must be resolved: $preference")
            }
        }
    }

    sealed interface Direction {
        data object Horizontal : Direction
        data object Vertical : Direction
    }

    sealed interface ViewerType {
        data object Pager : ViewerType
        data object Webtoon : ViewerType
    }
}

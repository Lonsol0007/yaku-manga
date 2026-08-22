package yaku.presentation.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A floating navigation bar, inset from the screen edges.
 *
 * Material's `NavigationBar` is a full-bleed strip pinned to the bottom of the window with a
 * pill behind the selected item - instantly recognisable, and the single strongest visual tie to
 * every other Material app. This replaces it with a rounded, translucent, bordered surface that
 * sits above the content with air around it.
 *
 * It renders in the Scaffold's `bottomBar` slot rather than floating over the content in a Box.
 * A true overlay looks the same but stops reserving space, and every list in the app would then
 * need its own bottom padding to keep the last row reachable - a change that is easy to make and
 * easy to miss on one screen.
 */
@Composable
fun YakuTaskbar(
    modifier: Modifier = Modifier,
    content: @Composable YakuTaskbarScope.() -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = TASKBAR_ALPHA),
        shape = RoundedCornerShape(TASKBAR_RADIUS),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 12.dp,
        modifier = modifier
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            YakuTaskbarScope(this).content()
        }
    }
}

/** Restricts taskbar items to the taskbar, so they cannot be dropped into an arbitrary Row. */
class YakuTaskbarScope internal constructor(private val row: androidx.compose.foundation.layout.RowScope) {

    /**
     * One destination.
     *
     * The label stays visible whether or not the item is selected. Material hides unselected
     * labels by default, which saves height at the cost of making every icon a guess.
     */
    @Composable
    fun Item(
        selected: Boolean,
        onClick: () -> Unit,
        label: String,
        icon: @Composable () -> Unit,
    ) = with(row) {
        val tint = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(ITEM_RADIUS))
                .clickable(onClick = onClick)
                .padding(vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.material3.LocalContentColor provides tint,
            ) {
                icon()
            }
            Text(
                text = label,
                color = tint,
                fontSize = 9.sp,
                lineHeight = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * Translucent enough to hint at the content scrolling beneath, opaque enough to stay legible
 * over a bright page of art.
 */
private const val TASKBAR_ALPHA = 0.86f
private val TASKBAR_RADIUS = 24.dp
private val ITEM_RADIUS = 16.dp

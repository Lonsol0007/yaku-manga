package yaku.presentation.theme

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import yaku.app.di.appGraph
import yaku.domain.ui.model.AppTheme
import yaku.presentation.theme.colorscheme.BaseColorScheme
import yaku.presentation.theme.colorscheme.CatppuccinColorScheme
import yaku.presentation.theme.colorscheme.GreenAppleColorScheme
import yaku.presentation.theme.colorscheme.LavenderColorScheme
import yaku.presentation.theme.colorscheme.MidnightDuskColorScheme
import yaku.presentation.theme.colorscheme.MonetColorScheme
import yaku.presentation.theme.colorscheme.MonochromeColorScheme
import yaku.presentation.theme.colorscheme.NordColorScheme
import yaku.presentation.theme.colorscheme.StrawberryColorScheme
import yaku.presentation.theme.colorscheme.TachiyomiColorScheme
import yaku.presentation.theme.colorscheme.TakoColorScheme
import yaku.presentation.theme.colorscheme.TealTurqoiseColorScheme
import yaku.presentation.theme.colorscheme.TidalWaveColorScheme
import yaku.presentation.theme.colorscheme.TokyoNightColorScheme
import yaku.presentation.theme.colorscheme.YakuColorScheme
import yaku.presentation.theme.colorscheme.YinYangColorScheme
import yaku.presentation.theme.colorscheme.YotsubaColorScheme

@Composable
fun TachiyomiTheme(
    appTheme: AppTheme? = null,
    amoled: Boolean? = null,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val uiPreferences = remember { context.appGraph.uiPreferences }
    BaseTachiyomiTheme(
        appTheme = appTheme ?: uiPreferences.appTheme.get(),
        isAmoled = amoled ?: uiPreferences.themeDarkAmoled.get(),
        content = content,
    )
}

@Composable
fun TachiyomiPreviewTheme(
    appTheme: AppTheme = AppTheme.DEFAULT,
    isAmoled: Boolean = false,
    content: @Composable () -> Unit,
) = BaseTachiyomiTheme(appTheme, isAmoled, content)

@Composable
private fun BaseTachiyomiTheme(
    appTheme: AppTheme,
    isAmoled: Boolean,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val isDark = isSystemInDarkTheme()
    MaterialExpressiveTheme(
        colorScheme = remember(appTheme, isDark, isAmoled) {
            getThemeColorScheme(
                context = context,
                appTheme = appTheme,
                isDark = isDark,
                isAmoled = isAmoled,
            )
        },
        typography = YakuTypography,
        shapes = YakuShapes,
        content = content,
    )
}

private fun getThemeColorScheme(
    context: Context,
    appTheme: AppTheme,
    isDark: Boolean,
    isAmoled: Boolean,
): ColorScheme {
    val colorScheme = if (appTheme == AppTheme.MONET) {
        MonetColorScheme(context)
    } else {
        colorSchemes.getOrDefault(appTheme, YakuColorScheme)
    }
    return colorScheme.getColorScheme(
        isDark = isDark,
        isAmoled = isAmoled,
        overrideDarkSurfaceContainers = appTheme != AppTheme.MONET,
    )
}

private val colorSchemes: Map<AppTheme, BaseColorScheme> = mapOf(
    AppTheme.DEFAULT to YakuColorScheme,
    AppTheme.CATPPUCCIN to CatppuccinColorScheme,
    AppTheme.TOKYONIGHT to TokyoNightColorScheme,
    AppTheme.GREEN_APPLE to GreenAppleColorScheme,
    AppTheme.LAVENDER to LavenderColorScheme,
    AppTheme.MIDNIGHT_DUSK to MidnightDuskColorScheme,
    AppTheme.MONOCHROME to MonochromeColorScheme,
    AppTheme.NORD to NordColorScheme,
    AppTheme.STRAWBERRY_DAIQUIRI to StrawberryColorScheme,
    AppTheme.TAKO to TakoColorScheme,
    AppTheme.TEALTURQUOISE to TealTurqoiseColorScheme,
    AppTheme.TIDAL_WAVE to TidalWaveColorScheme,
    AppTheme.YINYANG to YinYangColorScheme,
    AppTheme.YOTSUBA to YotsubaColorScheme,
)

package yaku.presentation.theme.colorscheme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Yaku Manga's own colours, and the app's default theme.
 *
 * Light is a near-white ground with white surfaces and a single red accent. Dark is not a
 * darkened version of that: it is near-black with *maroon* surfaces, so the red reads as the
 * material the app is built from rather than as a highlight sitting on grey. Reading happens in
 * the dark more often than not, and a warm dark surface is easier to sit behind a page of art
 * than the neutral charcoal every other reader uses.
 *
 * Key colours:
 * Accent  #D90429 light / #E21B3C dark
 * Ground  #F8F8FA light / #0A0708 dark
 * Surface #FFFFFF light / #170B0D dark
 */
internal object YakuColorScheme : BaseColorScheme() {

    override val darkScheme = darkColorScheme(
        primary = Color(0xFFE21B3C),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFF7A0A1C),
        onPrimaryContainer = Color(0xFFFFDADF),
        inversePrimary = Color(0xFFD90429),
        secondary = Color(0xFFE21B3C), // Unread badge
        onSecondary = Color(0xFFFFFFFF), // Unread badge text
        secondaryContainer = Color(0xFF3A1218), // Navigation selector pill
        onSecondaryContainer = Color(0xFFFFD9DE), // Navigation selector icon
        tertiary = Color(0xFF7ADC77), // Downloaded badge
        onTertiary = Color(0xFF00390A),
        tertiaryContainer = Color(0xFF005312),
        onTertiaryContainer = Color(0xFF95F990),
        background = Color(0xFF0A0708),
        onBackground = Color(0xFFF7F2F3),
        surface = Color(0xFF170B0D),
        onSurface = Color(0xFFF7F2F3),
        surfaceVariant = Color(0xFF241013),
        onSurfaceVariant = Color(0xFFB7A7AA), // Muted body text
        surfaceTint = Color(0xFFE21B3C),
        inverseSurface = Color(0xFFF7F2F3),
        inverseOnSurface = Color(0xFF170B0D),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        outline = Color(0xFF8A7276), // Hairline borders
        outlineVariant = Color(0xFF3A2A2D),
        surfaceContainerLowest = Color(0xFF0A0708),
        surfaceContainerLow = Color(0xFF130A0C),
        surfaceContainer = Color(0xFF170B0D),
        surfaceContainerHigh = Color(0xFF241013),
        surfaceContainerHighest = Color(0xFF2E181B),
    )

    override val lightScheme = lightColorScheme(
        primary = Color(0xFFD90429),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFFFDADF),
        onPrimaryContainer = Color(0xFF40000A),
        inversePrimary = Color(0xFFE21B3C),
        secondary = Color(0xFFD90429), // Unread badge
        onSecondary = Color(0xFFFFFFFF), // Unread badge text
        secondaryContainer = Color(0xFFFFE1E5), // Navigation selector pill
        onSecondaryContainer = Color(0xFF40000A), // Navigation selector icon
        tertiary = Color(0xFF006E1B), // Downloaded badge
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFF95F990),
        onTertiaryContainer = Color(0xFF002203),
        background = Color(0xFFF8F8FA),
        onBackground = Color(0xFF141416),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF141416),
        surfaceVariant = Color(0xFFF4F4F6),
        onSurfaceVariant = Color(0xFF707076), // Muted body text
        surfaceTint = Color(0xFFD90429),
        inverseSurface = Color(0xFF2A2A2E),
        inverseOnSurface = Color(0xFFF4F4F6),
        error = Color(0xFFBA1A1A),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
        outline = Color(0xFFBFBFC6), // Hairline borders
        outlineVariant = Color(0xFFE2E2E6),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFFAFAFC),
        surfaceContainer = Color(0xFFF4F4F6),
        surfaceContainerHigh = Color(0xFFEEEEF1),
        surfaceContainerHighest = Color(0xFFE8E8EC),
    )
}

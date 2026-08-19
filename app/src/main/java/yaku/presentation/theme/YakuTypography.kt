package yaku.presentation.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import yaku.R

/**
 * Poppins, the geometric sans the Yaku Manga wordmark is set in.
 *
 * Bundled rather than fetched through Google Fonts' downloadable-font provider: that provider is
 * part of Play Services, and an app whose whole point is that it does not phone home should not
 * make a network round trip to render its own title bar.
 */
private val Poppins = FontFamily(
    Font(R.font.poppins_regular, FontWeight.Normal),
    Font(R.font.poppins_medium, FontWeight.Medium),
    Font(R.font.poppins_semibold, FontWeight.SemiBold),
    Font(R.font.poppins_bold, FontWeight.Bold),
)

private val Default = Typography()

/**
 * Material 3's type scale with Poppins on the display, headline and title roles only.
 *
 * Body and label styles deliberately stay on the platform font. Poppins is a display face - its
 * circular bowls and wide sidebearings are what make the wordmark work, and are also what make a
 * dense settings list or a long synopsis harder to read at 14sp. Restricting it to the roles that
 * carry the brand keeps the app looking like its icon without paying for that at every line of
 * body copy, and keeps the bundled weights down to four.
 */
val YakuTypography: Typography = Default.copy(
    displayLarge = Default.displayLarge.copy(fontFamily = Poppins),
    displayMedium = Default.displayMedium.copy(fontFamily = Poppins),
    displaySmall = Default.displaySmall.copy(fontFamily = Poppins),

    headlineLarge = Default.headlineLarge.copy(fontFamily = Poppins),
    headlineMedium = Default.headlineMedium.copy(fontFamily = Poppins),
    headlineSmall = Default.headlineSmall.copy(fontFamily = Poppins),

    // Titles carry screen headings, app-bar text and manga titles - the most visible surfaces.
    titleLarge = Default.titleLarge.copy(fontFamily = Poppins, fontWeight = FontWeight.SemiBold),
    titleMedium = Default.titleMedium.copy(fontFamily = Poppins, fontWeight = FontWeight.Medium),
    titleSmall = Default.titleSmall.copy(fontFamily = Poppins, fontWeight = FontWeight.Medium),
)

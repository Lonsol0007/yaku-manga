package yaku.presentation.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * A rounder shape scale than Material's default.
 *
 * Material tops out at 28dp only for the extra-large role, and its medium sits at 12dp, which is
 * what gives a stock Compose app its recognisable slightly-boxy look. Pulling the middle of the
 * scale up to 18-22dp is most of what separates this from the app it was forked from, and it is
 * done once here rather than by scattering `RoundedCornerShape` through every screen.
 */
val YakuShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

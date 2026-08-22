package yaku.domain.ui

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.util.system.isDynamicColorAvailable
import yaku.core.common.preference.Preference
import yaku.core.common.preference.PreferenceStore
import yaku.core.common.preference.getEnum
import yaku.domain.ui.model.AppTheme
import yaku.domain.ui.model.TabletUiMode
import yaku.domain.ui.model.ThemeMode
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Inject
@SingleIn(AppScope::class)
class UiPreferences(
    preferenceStore: PreferenceStore,
) {

    val themeMode: Preference<ThemeMode> = preferenceStore.getEnum("pref_theme_mode_key", ThemeMode.SYSTEM)

    /**
     * Yaku's own palette, not the wallpaper's.
     *
     * This defaulted to MONET wherever dynamic colour was available, which is every device on
     * Android 12 and above - so the app painted itself from the user's wallpaper and its own
     * colours were never seen unless someone went looking in settings. For an app whose point is
     * that it is not the one it was forked from, shipping a theme that makes it look like every
     * other Material You app is the wrong default. Monet remains one tap away.
     */
    val appTheme: Preference<AppTheme> = preferenceStore.getEnum(
        "pref_app_theme",
        AppTheme.DEFAULT,
    )

    val themeDarkAmoled: Preference<Boolean> = preferenceStore.getBoolean("pref_theme_dark_amoled_key", false)

    val relativeTime: Preference<Boolean> = preferenceStore.getBoolean("relative_time_v2", true)

    val dateFormat: Preference<String> = preferenceStore.getString("app_date_format", "")

    val tabletUiMode: Preference<TabletUiMode> = preferenceStore.getEnum("tablet_ui_mode", TabletUiMode.AUTOMATIC)

    val imagesInDescription: Preference<Boolean> = preferenceStore.getBoolean("pref_render_images_description", true)

    companion object {
        fun dateFormat(format: String): DateTimeFormatter = when (format) {
            "" -> DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)
            else -> DateTimeFormatter.ofPattern(format, Locale.getDefault())
        }
    }
}

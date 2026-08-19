package yaku.data.updater

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.util.system.isFossBuildType
import eu.kanade.tachiyomi.util.system.isNightlyBuildType
import yaku.BuildConfig
import yaku.core.common.util.lang.withIOContext
import yaku.domain.release.interactor.GetApplicationRelease

@Inject
class AppUpdateChecker(
    private val getApplicationRelease: GetApplicationRelease,
) {

    suspend fun checkForUpdate(forceCheck: Boolean = false): GetApplicationRelease.Result {
        // Disable app update checks for older Android versions that we're going to drop support for
        // if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
        //     return GetApplicationRelease.Result.OsTooOld
        // }

        return withIOContext {
            val result = getApplicationRelease.await(
                GetApplicationRelease.Arguments(
                    isFossBuildType,
                    isNightlyBuildType,
                    BuildConfig.COMMIT_COUNT.toInt(),
                    BuildConfig.VERSION_NAME,
                    GITHUB_REPO,
                    forceCheck,
                ),
            )

            result
        }
    }
}

/**
 * Repository the in-app updater checks for new releases.
 *
 * TODO(yaku): point these at the Yaku Manga repositories before enabling the updater. They must
 * not stay on the mihonapp repositories: those APKs are signed with a different key and use a
 * different application id, so an "update" from them would fail to install. The updater is
 * compiled out unless the build is run with -Penable-updater, so a default build is unaffected.
 */
val GITHUB_REPO: String by lazy {
    if (isNightlyBuildType) {
        "yakumanga/yaku-manga-preview"
    } else {
        "yakumanga/yaku-manga"
    }
}

val RELEASE_TAG: String by lazy {
    if (isNightlyBuildType) {
        "r${BuildConfig.COMMIT_COUNT}"
    } else {
        "v${BuildConfig.VERSION_NAME}"
    }
}

val RELEASE_URL = "https://github.com/$GITHUB_REPO/releases/tag/$RELEASE_TAG"

package yaku.app.di

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metrox.viewmodel.MetroViewModelFactory
import dev.zacsweers.metrox.viewmodel.ViewModelGraph
import eu.kanade.tachiyomi.core.security.PrivacyPreferences
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.NetworkPreferences
import eu.kanade.tachiyomi.util.CrashLogUtil
import kotlinx.serialization.json.Json
import yaku.App
import yaku.core.metro.IsDebugBuild
import yaku.data.backup.create.BackupCreateJob
import yaku.data.backup.restore.BackupRestoreJob
import yaku.data.cache.ChapterCache
import yaku.data.download.DownloadCache
import yaku.data.download.DownloadJob
import yaku.data.download.DownloadManager
import yaku.data.library.LibraryUpdateJob
import yaku.data.library.MetadataUpdateJob
import yaku.data.notification.NotificationReceiver
import yaku.data.track.TrackerManager
import yaku.data.updater.AppUpdateChecker
import yaku.domain.backup.service.BackupPreferences
import yaku.domain.base.BasePreferences
import yaku.domain.category.interactor.GetCategories
import yaku.domain.category.interactor.ResetCategoryFlags
import yaku.domain.download.service.DownloadPreferences
import yaku.domain.extension.interactor.GetExtensionStoreCountAsFlow
import yaku.domain.extension.interactor.TrustExtension
import yaku.domain.library.service.LibraryPreferences
import yaku.domain.manga.interactor.GetFavorites
import yaku.domain.manga.interactor.ResetViewerFlags
import yaku.domain.source.service.SourceManager
import yaku.domain.source.service.SourcePreferences
import yaku.domain.storage.service.StoragePreferences
import yaku.domain.track.interactor.AddTracks
import yaku.domain.track.interactor.InsertTrack
import yaku.domain.track.service.DelayedTrackingUpdateJob
import yaku.domain.track.service.TrackPreferences
import yaku.domain.ui.UiPreferences
import yaku.extension.ExtensionManager
import yaku.extension.util.ExtensionInstallActivity
import yaku.ui.base.delegate.SecureActivityDelegateImpl
import yaku.ui.main.MainActivity
import yaku.ui.reader.ReaderActivity
import yaku.ui.reader.setting.ReaderPreferences
import yaku.ui.reader.translation.PageTranslator
import yaku.ui.reader.translation.TranslationPreferences
import yaku.ui.setting.track.BaseOAuthLoginActivity
import yaku.ui.webview.WebViewActivity

@DependencyGraph(
    scope = AppScope::class,
    bindingContainers = [AppBindings::class],
)
interface AppGraph : ViewModelGraph {
    fun inject(app: App)
    fun inject(mainActivity: MainActivity)
    fun inject(readerActivity: ReaderActivity)
    fun inject(webViewActivity: WebViewActivity)
    fun inject(baseOAuthLoginActivity: BaseOAuthLoginActivity)
    fun inject(libraryUpdateJob: LibraryUpdateJob)
    fun inject(metadataUpdateJob: MetadataUpdateJob)
    fun inject(backupRestoreJob: BackupRestoreJob)
    fun inject(backupCreateJob: BackupCreateJob)
    fun inject(delayedTrackingUpdateJob: DelayedTrackingUpdateJob)
    fun inject(downloadJob: DownloadJob)
    fun inject(notificationReceiver: NotificationReceiver)
    fun inject(notificationReceiver: SecureActivityDelegateImpl)
    fun inject(extensionInstallActivity: ExtensionInstallActivity)

    val context: Context

    val viewModelFactory: MetroViewModelFactory

    val basePreferences: BasePreferences
    val uiPreferences: UiPreferences
    val readerPreferences: ReaderPreferences
    val translationPreferences: TranslationPreferences
    val networkPreferences: NetworkPreferences
    val libraryPreferences: LibraryPreferences
    val sourcePreferences: SourcePreferences
    val trackPreferences: TrackPreferences
    val backupPreferences: BackupPreferences
    val storagePreferences: StoragePreferences
    val privacyPreferences: PrivacyPreferences
    val securityPreferences: SecurityPreferences
    val downloadPreferences: DownloadPreferences

    val crashLogUtil: CrashLogUtil

    val downloadManager: DownloadManager

    val updateChecker: AppUpdateChecker

    val trustExtension: TrustExtension

    val sourceManager: SourceManager
    val trackerManager: TrackerManager
    val extensionManager: ExtensionManager
    val pageTranslator: PageTranslator

    val chapterCache: ChapterCache
    val downloadCache: DownloadCache

    val json: Json
    val networkHelper: NetworkHelper

    val getFavorites: GetFavorites
    val getCategories: GetCategories
    val resetViewerFlags: ResetViewerFlags
    val resetCategoryFlags: ResetCategoryFlags
    val addTracks: AddTracks
    val insertTrack: InsertTrack

    val getExtensionStoreCountAsFlow: GetExtensionStoreCountAsFlow

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(@Provides context: Context, @Provides @IsDebugBuild isDebugBuild: Boolean): AppGraph
    }
}

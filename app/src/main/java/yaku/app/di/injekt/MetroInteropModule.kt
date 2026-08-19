package yaku.app.di.injekt

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.network.JavaScriptEngine
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import nl.adaptivity.xmlutil.serialization.XML
import uy.kohesive.injekt.api.InjektModule
import uy.kohesive.injekt.api.InjektRegistrar
import uy.kohesive.injekt.api.addSingleton
import yaku.core.common.preference.PreferenceStore
import yaku.data.cache.CoverCache
import yaku.domain.track.service.TrackPreferences
import yaku.extension.ExtensionManager

@Inject
class MetroInteropModule(
    private val json: Json,
    private val protoBuf: ProtoBuf,
    private val xml: XML,

    private val networkHelper: NetworkHelper,
    private val javaScriptEngine: JavaScriptEngine,

    private val preferenceStore: PreferenceStore,
    private val trackPreferences: TrackPreferences,

    private val extensionManager: ExtensionManager,

    private val coverCache: CoverCache,
) : InjektModule {

    override fun InjektRegistrar.registerInjectables() {
        addSingleton(json)
        addSingleton(protoBuf)
        addSingleton(xml)

        addSingleton(networkHelper)
        addSingleton(javaScriptEngine)

        addSingleton(preferenceStore)
        addSingleton(trackPreferences)

        addSingleton(extensionManager)

        addSingleton(coverCache)
    }
}

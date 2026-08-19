package yaku.domain.source.model

import android.content.Context
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import yaku.app.di.appGraph
import yaku.domain.source.model.Source

val Source.icon: ImageBitmap?
    get() {
        return Injekt.get<Context>().appGraph.extensionManager.getAppIconForSource(id)
            ?.toBitmap()
            ?.asImageBitmap()
    }

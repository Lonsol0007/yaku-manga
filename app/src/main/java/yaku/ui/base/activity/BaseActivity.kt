package yaku.ui.base.activity

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import eu.kanade.tachiyomi.util.system.prepareTabletUiContext
import yaku.app.di.appGraph
import yaku.ui.base.delegate.SecureActivityDelegate
import yaku.ui.base.delegate.SecureActivityDelegateImpl
import yaku.ui.base.delegate.ThemingDelegate
import yaku.ui.base.delegate.ThemingDelegateImpl

open class BaseActivity :
    AppCompatActivity(),
    SecureActivityDelegate by SecureActivityDelegateImpl(),
    ThemingDelegate by ThemingDelegateImpl() {

    override fun attachBaseContext(newBase: Context) {
        val uiPreferences = newBase.appGraph.uiPreferences
        super.attachBaseContext(newBase.prepareTabletUiContext(uiPreferences))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyAppTheme(this)
        super.onCreate(savedInstanceState)
    }
}

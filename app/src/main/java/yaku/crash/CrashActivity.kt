package yaku.crash

import android.content.Intent
import android.os.Bundle
import androidx.core.view.WindowCompat
import eu.kanade.tachiyomi.util.view.setComposeContent
import yaku.presentation.crash.CrashScreen
import yaku.ui.base.activity.BaseActivity
import yaku.ui.main.MainActivity

class CrashActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        val exception = GlobalExceptionHandler.getThrowableFromIntent(intent)
        setComposeContent {
            CrashScreen(
                exception = exception,
                onRestartClick = {
                    finishAffinity()
                    startActivity(Intent(this@CrashActivity, MainActivity::class.java))
                },
            )
        }
    }
}

package dev.wadackel.limenty

import android.app.Application
import dev.wadackel.limenty.core.common.log.LogTags
import dev.wadackel.limenty.core.common.log.TermLog
import dev.wadackel.limenty.log.AppLogging

class LimentyApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Before anything that could read from disk, so the first offender is still on the stack.
        installStrictMode()
        AppLogging.install(this)
        container = AppContainer(this)
        TermLog.logger(LogTags.LIFECYCLE).i(
            "app.create",
            "versionName" to BuildConfig.VERSION_NAME,
            "debug" to BuildConfig.DEBUG,
        )
    }
}

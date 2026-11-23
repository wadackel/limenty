package dev.wadackel.limenty

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.window.layout.WindowMetricsCalculator
import dev.wadackel.limenty.core.common.log.LogTags
import dev.wadackel.limenty.core.common.log.TermLog
import dev.wadackel.limenty.ui.LimentyApp
import dev.wadackel.limenty.ui.theme.LimentyTheme
import dev.wadackel.limenty.ui.theme.enableLimentyEdgeToEdge

class MainActivity : ComponentActivity() {
    private val log = TermLog.logger(LogTags.LIFECYCLE)
    private val resizeLog = TermLog.logger(LogTags.RESIZE)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableLimentyEdgeToEdge()
        super.onCreate(savedInstanceState)
        log.i("activity.create", "restored" to (savedInstanceState != null))
        logWindowMetrics("create")

        requestNotificationPermissionIfNeeded()

        val container = (application as LimentyApplication).container
        setContent {
            LimentyTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    LimentyApp(container)
                }
            }
        }
    }

    /**
     * The keep-alive service needs a visible notification, and from Android 13 that needs consent.
     * Denial is not fatal — the session simply loses its best-effort protection in the background —
     * so the result is logged rather than acted on.
     */
    private fun requestNotificationPermissionIfNeeded() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) return
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
            log.i("notifications.permission", "granted" to allowed)
        }.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // Discovery follows the activity rather than the host list: a reconnect from the terminal
    // needs a LAN host's current address as much as a tap in the list does.
    override fun onStart() {
        super.onStart()
        log.i("activity.start")
        (application as LimentyApplication).container.lan.start()
    }

    override fun onStop() {
        super.onStop()
        log.i("activity.stop")
        (application as LimentyApplication).container.lan.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        log.i("activity.destroy", "finishing" to isFinishing)
    }

    /**
     * The manifest claims every size-affecting config change, so this callback is the only place
     * a fold, rotation or split-screen resize becomes observable — and those are exactly the
     * events a PTY-size bug has to be reconstructed from.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        resizeLog.i(
            "config.change",
            "orientation" to newConfig.orientation,
            "screenWidthDp" to newConfig.screenWidthDp,
            "screenHeightDp" to newConfig.screenHeightDp,
            "smallestWidthDp" to newConfig.smallestScreenWidthDp,
            "density" to newConfig.densityDpi,
            "keyboard" to newConfig.keyboard,
            "hardKeyboardHidden" to newConfig.hardKeyboardHidden,
        )
        logWindowMetrics("config")
    }

    private fun logWindowMetrics(cause: String) {
        val metrics = WindowMetricsCalculator.getOrCreate().computeCurrentWindowMetrics(this)
        val bounds = metrics.bounds
        resizeLog.i(
            "window.metrics",
            "cause" to cause,
            "width" to bounds.width(),
            "height" to bounds.height(),
            "density" to resources.displayMetrics.density,
        )
    }
}

val   exp_badly_formatted=1

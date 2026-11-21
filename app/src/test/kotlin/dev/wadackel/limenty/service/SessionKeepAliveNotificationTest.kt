package dev.wadackel.limenty.service

import android.app.Application
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The notification is the only control that ends a backgrounded session, so its Disconnect has to
 * mean the session the notification is showing — including after a reconnect has replaced it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class SessionKeepAliveNotificationTest {
    private val service = Robolectric.buildService(SessionKeepAliveService::class.java).create().get()

    private fun disconnectTarget(hostName: String, sessionId: String): String? {
        val notification = service.buildNotification(hostName, sessionId)
        return shadowOf(notification.actions[0].actionIntent)
            .savedIntent
            .getStringExtra(SessionKeepAliveService.EXTRA_SESSION_ID)
    }

    @Test
    fun `a notification built for a new session disconnects that session`() {
        assertThat(disconnectTarget("Test one", "s1")).isEqualTo("s1")

        assertThat(disconnectTarget("Test two", "s2")).isEqualTo("s2")
    }
}

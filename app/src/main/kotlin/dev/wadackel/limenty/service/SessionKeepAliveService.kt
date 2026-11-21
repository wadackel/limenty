package dev.wadackel.limenty.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import dev.wadackel.limenty.LimentyApplication
import dev.wadackel.limenty.MainActivity
import dev.wadackel.limenty.R
import dev.wadackel.limenty.core.common.log.LogTags
import dev.wadackel.limenty.core.common.log.TermLog
import dev.wadackel.limenty.core.session.SessionId

/**
 * Keeps the process alive while a session is connected.
 *
 * Android is free to kill a backgrounded process, and with it every SSH connection. Keeping the
 * connection is best effort rather than a guarantee, because losing it costs little: the work
 * lives on the remote (in tmux, say), and a dropped session is one tap from reconnecting. So this
 * is a plain foreground service with one compact notification — not a wake lock, and not an
 * attempt to outlast the system.
 */
class SessionKeepAliveService : Service() {
    private val log = TermLog.logger(LogTags.SESSION)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val sessionId = intent?.getStringExtra(EXTRA_SESSION_ID).orEmpty()
        if (intent?.action == ACTION_DISCONNECT) {
            log.i("keepAlive.disconnect", "session" to sessionId)
            val container = (application as? LimentyApplication)?.container
            container?.connections?.launchClose(SessionId(sessionId))
            // The notification is the only place this action exists, so it goes with the session.
            stopSelf()
            return START_NOT_STICKY
        }

        val hostName = intent?.getStringExtra(EXTRA_HOST_NAME).orEmpty()
        startForeground(NOTIFICATION_ID, buildNotification(hostName, sessionId))
        log.i("keepAlive.start", "host" to hostName)
        // Not sticky: a session cannot survive process death anyway, so restarting the
        // service without one would only show a notification for a connection that no longer exists.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        log.i("keepAlive.stop")
    }

    internal fun buildNotification(hostName: String, sessionId: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Active session", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while an SSH session is connected."
                setShowBadge(false)
            },
        )

        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        // Leaving the app is the common way to background a session, which puts the notification
        // between the user and the only control that ends it. The notification is there only
        // because a foreground service has to show one, so it stays compact: one action, not a set.
        //
        // UPDATE_CURRENT because the request code and the action are fixed while the session id
        // is not: without it the system handed back the PendingIntent built for the previous
        // session, and after a reconnect the notification's Disconnect ended a session that no
        // longer existed. The creator may still rewrite the extras of an IMMUTABLE intent; what
        // immutability denies is the receiver doing so.
        val disconnect = PendingIntent.getService(
            this,
            1,
            Intent(this, SessionKeepAliveService::class.java)
                .setAction(ACTION_DISCONNECT)
                .putExtra(EXTRA_SESSION_ID, sessionId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(if (hostName.isEmpty()) "Connected" else "Connected to $hostName")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(
                Notification.Action.Builder(null, getString(R.string.notification_disconnect), disconnect).build(),
            )
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "session"
        private const val NOTIFICATION_ID = 1
        private const val EXTRA_HOST_NAME = "hostName"
        internal const val EXTRA_SESSION_ID = "sessionId"
        private const val ACTION_DISCONNECT = "dev.wadackel.limenty.DISCONNECT"

        /**
         * Starting a foreground service is only permitted while the app is itself in the
         * foreground (API 31+). A connection that completes after the user has already left —
         * tap, go home, authentication finishes — would otherwise crash the process, so the
         * refusal is caught: losing the keep-alive is a degraded session, not a fatal error.
         */
        fun start(context: Context, hostName: String, sessionId: SessionId) {
            val intent = Intent(context, SessionKeepAliveService::class.java)
                .putExtra(EXTRA_HOST_NAME, hostName)
                .putExtra(EXTRA_SESSION_ID, sessionId.value)
            runCatching { context.startForegroundService(intent) }
                .onFailure { error ->
                    TermLog.logger(LogTags.SESSION).w(error, "keepAlive.startRefused", "host" to hostName)
                }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SessionKeepAliveService::class.java))
        }
    }
}

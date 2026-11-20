package org.bimz.reforge.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.bimz.reforge.MainActivity
import org.bimz.reforge.R
import org.bimz.reforge.ReForgeApplication

class ReForgeSessionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_EXIT_ALL) {
            val app = application as? ReForgeApplication
            app?.sharedViewModel?.shutdownAllBackgroundSessions()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
            return START_NOT_STICKY
        }

        val notif = buildNotification()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIF_ID,
                    notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIF_ID, notif)
            }
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep service & Radare2 child sessions alive in background like Termux
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification())
        super.onTaskRemoved(rootIntent)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val existing = nm.getNotificationChannel(CHANNEL_ID)
            if (existing == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "RE FORGE Background Sessions",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Keeps Radare2 sessions and tasks active when app is closed"
                    setShowBadge(false)
                }
                nm.createNotificationChannel(channel)
            }
        }
    }

    private fun buildNotification(): Notification {
        val app = application as? ReForgeApplication
        val state = app?.sharedViewModel?.uiState?.value
        val sessionCount = state?.terminalSessions?.size ?: 1
        val r2ActiveCount = state?.terminalSessions?.count { it.r2Mode } ?: 0
        val targetName = state?.activeFile?.name ?: "No active target"

        val subtitle = if (r2ActiveCount > 0) {
            "$sessionCount session(s) ($r2ActiveCount r2) • Target: $targetName"
        } else {
            "$sessionCount shell session(s) ready • Target: $targetName"
        }

        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            this,
            100,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val exitIntent = Intent(this, ReForgeSessionService::class.java).apply {
            action = ACTION_EXIT_ALL
        }
        val exitPending = PendingIntent.getService(
            this,
            101,
            exitIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("RE FORGE • Active in Background")
            .setContentText(subtitle)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPending)
            .addAction(0, "Open", openPending)
            .addAction(0, "Stop & Exit", exitPending)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "reforge_bg_session"
        const val NOTIF_ID = 4201
        const val ACTION_EXIT_ALL = "org.bimz.reforge.action.EXIT_ALL"

        fun sync(context: Context) {
            runCatching {
                val intent = Intent(context, ReForgeSessionService::class.java)
                ContextCompat.startForegroundService(context, intent)
            }
        }
    }
}

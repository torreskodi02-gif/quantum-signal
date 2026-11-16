package app.quantumsignal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

/**
 * Foreground service that keeps Quantum Signal polling for new messages
 * while the app is backgrounded — the thing a WebView cannot do.
 *
 * The worker thread loops: one long-poll cycle (Poller.pollOnce, which
 * blocks up to 45s in the foreground / 15s in the background), then a
 * short sleep. On error it backs off 5s and retries; it never crashes.
 *
 * Manifest note (another file declares this): the service must be
 * declared with android:foregroundServiceType="remoteMessaging".
 */
class QsService : Service() {

    companion object {
        private const val NOTIF_ID = 1
        private const val CHANNEL_SERVICE = "qs-service"

        /** Start the service (foreground on Android 8+). */
        fun start(ctx: Context) {
            val appCtx = ctx.applicationContext
            val intent = Intent(appCtx, QsService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appCtx.startForegroundService(intent)
            } else {
                appCtx.startService(intent)
            }
        }

        /** Stop the service. */
        fun stop(ctx: Context) {
            val appCtx = ctx.applicationContext
            appCtx.stopService(Intent(appCtx, QsService::class.java))
        }

        /**
         * Tell the poller whether the app is backgrounded. Poller.background
         * is @Volatile and the service loop reads it via pollOnce(), so
         * setting it directly is enough — no intent round-trip needed.
         */
        fun setBackground(ctx: Context, bg: Boolean) {
            Poller.background = bg
        }
    }

    @Volatile
    private var running = false
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must be called promptly after startForegroundService (5s limit).
        startForeground(NOTIF_ID, foregroundNotification())
        synchronized(this) {
            if (worker?.isAlive != true) {
                running = true
                worker = Thread({
                    while (running) {
                        try {
                            val ok = Poller.pollOnce(applicationContext)
                            // The long-poll already waited; 1s between
                            // successful cycles, 5s back-off after errors.
                            Thread.sleep(if (ok) 1000L else 5000L)
                        } catch (e: InterruptedException) {
                            break
                        } catch (e: Exception) {
                            Dbg.log(applicationContext, "service loop: ${e.message}")
                            try {
                                Thread.sleep(5000L)
                            } catch (ie: InterruptedException) {
                                break
                            }
                        }
                    }
                }, "qs-poller").also { it.start() }
            }
        }
        // Restart if Android kills the service.
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        worker?.interrupt()
        worker = null
        super.onDestroy()
    }

    /** Ongoing low-priority notification while the service runs. */
    private fun foregroundNotification(): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            nm.getNotificationChannel(CHANNEL_SERVICE) == null
        ) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_SERVICE, "Quantum Signal", NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // Notification.Builder(ctx, channelId) needs API 26+; minSdk is
        // 23, so pre-O devices use the no-channel builder.
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_SERVICE)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this).setPriority(Notification.PRIORITY_LOW)
        }
        return builder
            .setContentTitle("Quantum Signal is running")
            .setContentText("Listening for messages")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }
}

/**
 * Restart polling after a reboot, but only if a user is logged in.
 * Never crashes on boot: everything is guarded.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        try {
            if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
            if (Store.load(ctx)) QsService.start(ctx)
        } catch (e: Exception) {
            try {
                Dbg.log(ctx, "boot receiver: ${e.message}")
            } catch (_: Exception) {
            }
        }
    }
}

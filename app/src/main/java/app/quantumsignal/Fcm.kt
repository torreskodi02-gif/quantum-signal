package app.quantumsignal

import android.content.Context

/**
 * Firebase Cloud Messaging glue for Quantum Signal (native).
 *
 * FCM activates ONLY once Kodi does two things:
 *   1. adds google-services.json to the app module (android/app/google-services.json),
 *   2. sets the FCM_KEY service-account secret on the Cloudflare worker
 *      (and redeploys it with `bash cloudflare.sh`).
 * Until then FirebaseMessagingService.onNewToken never fires, no token is
 * ever registered, and this code is inert — the app builds and runs fine
 * without any Firebase configuration (the google-services plugin is
 * deliberately NOT applied in build.gradle for exactly this reason).
 *
 * What FCM is for: waking a closed/killed app. When a message is sent,
 * the server (worker-v090.js, pushNotify) sends a high-priority FCM
 * notification carrying only the sender's name — never message content
 * (the server only ever sees ciphertext anyway). Android shows that
 * notification itself when the app is dead; when the app is alive,
 * FcmService.onMessageReceived fires and we show the notification
 * ourselves and trigger an immediate poll to fetch the real message.
 */
object Fcm {
    private const val PREFS = "qs"
    private const val KEY_TOKEN = "fcm_token"

    /**
     * Called when Firebase issues (or refreshes) a device token, and
     * after login with the stored token (the UI re-registers it then).
     * Saves the token locally, then — if logged in — POSTs it to the
     * server (POST /push-token {u, token} + x-token auth) on a
     * background thread so a slow network can never block the UI.
     * All failures are swallowed and logged: push registration must
     * never break login or messaging.
     */
    fun onToken(ctx: Context, token: String) {
        val appCtx = ctx.applicationContext
        try {
            appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_TOKEN, token).apply()
        } catch (e: Exception) {
            Dbg.log(appCtx, "Fcm: save token failed: ${e.message}")
            return
        }
        val acc = try { Store.account() } catch (e: Exception) { null }
        if (acc == null) {
            // Not logged in yet — the token is stored and will be
            // registered after login when the UI calls onToken again.
            Dbg.log(appCtx, "Fcm: token saved, no account yet")
            return
        }
        Thread {
            try {
                Api.pushToken(acc.user, acc.token, token)
                Dbg.log(appCtx, "Fcm: token registered for ${acc.user}")
            } catch (e: Exception) {
                Dbg.log(appCtx, "Fcm: token register failed: ${e.message}")
            }
        }.start()
    }

    /**
     * Called when a push arrives while the app is alive. Shows a native
     * notification (never the message content — the push carries only
     * the sender's name) and kicks the poller so the real, encrypted
     * message is fetched and decrypted immediately. A push must never
     * crash the app, so everything is guarded.
     */
    fun onMessage(ctx: Context, title: String?, body: String?, chat: String?) {
        val appCtx = ctx.applicationContext
        try {
            Poller.notifyNew(
                appCtx,
                from = title ?: "Quantum Signal",
                text = body ?: "New message",
                chatId = chat ?: ""
            )
        } catch (e: Exception) {
            Dbg.log(appCtx, "Fcm: notify failed: ${e.message}")
        }
        try {
            // Start the foreground poller if it is not already running
            // so the pushed-about message is fetched right away.
            Poller.start(appCtx)
        } catch (e: Exception) {
            Dbg.log(appCtx, "Fcm: poll kick failed: ${e.message}")
        }
    }

    /** The last device token Firebase gave us, or null if none yet. */
    fun storedToken(ctx: Context): String? = try {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TOKEN, null)
    } catch (e: Exception) {
        null
    }
}

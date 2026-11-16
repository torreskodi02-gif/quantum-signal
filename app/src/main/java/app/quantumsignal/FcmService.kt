package app.quantumsignal

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Firebase Cloud Messaging entry point.
 *
 * Registered in AndroidManifest.xml with the MESSAGING_EVENT
 * intent-filter. The firebase-messaging library delivers here.
 *
 * NOTE on the killed-app case: the server sends a *notification*
 * payload (title "@sender", body "New message") plus data
 * {chat: sender}. When the app is killed, Android shows that
 * notification itself and onMessageReceived does NOT fire — that is
 * expected and fine; tapping the notification opens the app, which
 * polls and fetches the message. When the app is alive (foreground
 * or background service running), onMessageReceived fires and we
 * show the notification ourselves via Fcm.onMessage and trigger an
 * immediate poll.
 *
 * Everything is wrapped in try/catch: if Firebase is not configured
 * (no google-services.json yet) this service simply never fires, and
 * a missing/broken Firebase setup can never crash the app.
 */
class FcmService : FirebaseMessagingService() {

    /** A new/rotated device token: save it and register it with the server. */
    override fun onNewToken(token: String) {
        try {
            Fcm.onToken(applicationContext, token)
        } catch (e: Exception) {
            // Firebase not configured, or registration failed — push
            // simply stays inert. Never crash on a token refresh.
            try { Dbg.log(applicationContext, "FcmService: onNewToken failed: ${e.message}") } catch (_: Exception) {}
        }
    }

    /** A push arrived while the app is alive: notify + poll now. */
    override fun onMessageReceived(msg: RemoteMessage) {
        try {
            val title = msg.notification?.title
            val body = msg.notification?.body
            val chat = msg.data["chat"]
            Fcm.onMessage(applicationContext, title, body, chat)
        } catch (e: Exception) {
            // A malformed push must never crash the app.
            try { Dbg.log(applicationContext, "FcmService: onMessageReceived failed: ${e.message}") } catch (_: Exception) {}
        }
    }
}

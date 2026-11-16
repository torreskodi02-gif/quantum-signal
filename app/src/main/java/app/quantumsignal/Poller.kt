package app.quantumsignal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Background polling + notifications for Quantum Signal (native).
 *
 * Runs on whatever thread calls it — the QsService worker thread, or the
 * UI thread when the UI wants an immediate catch-up (pollOnce). Never
 * touches the UI thread and never logs tokens or private keys.
 *
 * Server shapes (see worker-v090.js):
 *  - POST /inbox {u, wait, ack:[ids]} -> {m:[{id, from, ct, iv, ts}]}.
 *    Messages stay on the server until acked; acking happens by passing
 *    their ids in the NEXT poll's `ack` list.
 *  - POST /send {from, to, ct, iv} -> {ok, id}.
 *  - GET /user/{name} -> {pub}.
 * Plaintext is JSON: {"k":"m","id","t","q"} chat, {"k":"d","ids"}
 * delivered receipt, {"k":"r","ids"} read receipt.
 */
object Poller {

    private const val CHANNEL_MESSAGES = "qs-messages"
    private const val BACKUP_INTERVAL_MS = 5 * 60 * 1000L

    /** True when the app is backgrounded; the UI sets this. Shortens polls. */
    @Volatile
    var background: Boolean = false

    /** Ids successfully processed this cycle; acked by the NEXT poll. */
    @Volatile
    private var ackIds: List<String> = emptyList()

    /** Message ids that failed to decrypt -> failure count. Acked at 3. */
    private val retryCounts = ConcurrentHashMap<String, Int>()

    /** When the last automatic backup upload succeeded (ms epoch). */
    private var lastBackupAt: Long = 0L

    /** Start the foreground service, but only when a user is logged in. */
    fun start(ctx: Context) {
        if (Store.account() != null) QsService.start(ctx.applicationContext)
    }

    /** Stop the foreground service. */
    fun stop(ctx: Context) {
        QsService.stop(ctx.applicationContext)
    }

    /**
     * Tell the poller (and the service) whether the app is backgrounded.
     * Poller.background is @Volatile, so the service loop picks the new
     * value up via pollOnce() without any intent round-trip.
     */
    fun setBackground(ctx: Context, bg: Boolean) {
        background = bg
        QsService.setBackground(ctx.applicationContext, bg)
    }

    /**
     * One long-poll cycle. Returns true if the cycle completed (even if it
     * delivered no messages), false on error / no account — the service
     * loop backs off and retries. Never throws, never acks on failure.
     */
    fun pollOnce(ctx: Context): Boolean {
        val acct = Store.account() ?: return false
        val wait = if (background) 15 else 45

        // (b) Long-poll, acking what the PREVIOUS cycle processed.
        val inboxJson: JSONObject = try {
            Api.inbox(acct.user, wait, ackIds, acct.token)
        } catch (e: Exception) {
            Dbg.log(ctx, "poll: inbox failed: ${e.message}")
            return false
        }

        var changed = false
        val ackNext = mutableListOf<String>()
        // Sender -> ids of NEW chat messages, for one delivered receipt each.
        val newBySender = mutableMapOf<String, MutableList<String>>()

        // (c) Process each delivered message. One bad message must not
        // abort the cycle, so each iteration is guarded individually.
        val arr = inboxJson.optJSONArray("m") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            val id = m.optString("id", "")
            if (id.isEmpty()) continue
            val from = m.optString("from", "")
            if (from.isEmpty()) {
                // Malformed envelope; nothing can ever fix it, so drop it.
                Dbg.log(ctx, "poll: message $id has no sender, dropping")
                ackNext.add(id)
                continue
            }
            try {
                val senderPub = peerPub(ctx, from)
                if (senderPub == null) {
                    // No key, so we cannot decrypt. Count it like a
                    // decrypt failure: retried, then dropped at 3.
                    failDecrypt(ctx, id, ackNext)
                    continue
                }
                val plain: String = try {
                    Crypto.decryptMsg(senderPub, acct.priv, m.optString("iv"), m.optString("ct"))
                } catch (e: Exception) {
                    failDecrypt(ctx, id, ackNext)
                    continue
                }
                val obj: JSONObject = try {
                    JSONObject(plain)
                } catch (e: Exception) {
                    // Decrypted but not JSON: corrupt, will never parse.
                    Dbg.log(ctx, "poll: message $id is not JSON, dropping")
                    ackNext.add(id)
                    continue
                }
                when (obj.optString("k")) {
                    "m" -> {
                        val text = obj.optString("t", "")
                        val q = if (obj.has("q") && !obj.isNull("q")) obj.optString("q") else null
                        // Cache the sender's pub on the chat (addMsg would
                        // create the chat without it). addMsg does NOT touch
                        // unread — the caller owns it, so bump it here for a
                        // genuinely new incoming message.
                        val chat = Store.getOrCreateChat(from, senderPub)
                        val msg = Store.Msg(
                            id = id, me = false, t = text,
                            ts = m.optLong("ts", System.currentTimeMillis()),
                            st = 1, q = q
                        )
                        if (Store.addMsg(from, msg)) {
                            changed = true
                            chat.unread++
                            newBySender.getOrPut(from) { mutableListOf() }.add(id)
                            notifyNew(ctx, from, text, from)
                        }
                        ackNext.add(id)
                    }
                    "d" -> {
                        val ids = obj.optJSONArray("ids")
                        if (ids != null) {
                            for (j in 0 until ids.length()) {
                                Store.setMsgStatus(from, ids.optString(j), 2)
                            }
                            changed = true
                        }
                        ackNext.add(id)
                    }
                    "r" -> {
                        val ids = obj.optJSONArray("ids")
                        if (ids != null) {
                            for (j in 0 until ids.length()) {
                                Store.setMsgStatus(from, ids.optString(j), 3)
                            }
                            changed = true
                        }
                        ackNext.add(id)
                    }
                    "call" -> {
                        // Calls are a stub in v1: log and drop the offer.
                        Dbg.log(ctx, "poll: call message from $from ignored (stub)")
                        ackNext.add(id)
                    }
                    else -> {
                        Dbg.log(ctx, "poll: unknown kind ${obj.optString("k")} from $from, dropping")
                        ackNext.add(id)
                    }
                }
            } catch (e: Exception) {
                // Unexpected failure handling this one message: log it and
                // do NOT ack, so it is re-delivered next cycle.
                Dbg.log(ctx, "poll: message $id failed: ${e.message}")
            }
        }

        // (d) One delivered receipt per sender that sent a new message.
        for ((sender, ids) in newBySender) {
            if (ids.isNotEmpty()) sendReceipt(ctx, sender, "d", ids)
        }
        // These ids are acked by the NEXT poll's /inbox call.
        ackIds = ackNext

        // Retry the unsent queue: re-encrypt and re-send each pending msg.
        for ((chatId, msg) in Store.pending()) {
            try {
                val pub = peerPub(ctx, chatId) ?: continue
                val json = JSONObject().apply {
                    put("k", "m")
                    put("id", msg.id)
                    put("t", msg.t)
                    if (msg.q != null) put("q", msg.q)
                }
                val enc = Crypto.encryptMsg(pub, acct.priv, json.toString())
                Api.send(acct.user, chatId, enc.ct, enc.iv, acct.token)
                Store.setMsgStatus(chatId, msg.id, 1)
                changed = true
            } catch (e: Exception) {
                Dbg.log(ctx, "poll: resend to $chatId failed: ${e.message}")
            }
        }

        if (changed) {
            try {
                Store.save(ctx)
            } catch (e: Exception) {
                Dbg.log(ctx, "poll: save failed: ${e.message}")
            }
            backupIfNeeded(ctx)
        }
        return true
    }

    /**
     * Send a delivered ("d") or read ("r") receipt to [to].
     * The UI calls this with "r" when the user opens a chat; pollOnce
     * calls it with "d" when a message arrives. Never throws: a failed
     * receipt must never crash the app.
     */
    fun sendReceipt(ctx: Context, to: String, kind: String, ids: List<String>) {
        if (ids.isEmpty()) return
        val acct = Store.account() ?: return
        try {
            val pub = peerPub(ctx, to) ?: return
            val json = JSONObject().apply {
                put("k", kind)
                put("ids", JSONArray(ids))
            }
            val enc = Crypto.encryptMsg(pub, acct.priv, json.toString())
            Api.send(acct.user, to, enc.ct, enc.iv, acct.token)
        } catch (e: Exception) {
            Dbg.log(ctx, "receipt $kind to $to failed: ${e.message}")
        }
    }

    /**
     * Post a native notification for a new incoming message.
     * Respects the "notif" toggle and "hidePrev" (hide message preview)
     * preferences in the "qs" SharedPreferences.
     */
    fun notifyNew(ctx: Context, from: String, text: String, chatId: String) {
        try {
            val prefs = ctx.getSharedPreferences("qs", 0)
            if (!prefs.getBoolean("notif", true)) return
            val hidePrev = prefs.getBoolean("hidePrev", false)

            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                nm.getNotificationChannel(CHANNEL_MESSAGES) == null
            ) {
                val ch = NotificationChannel(
                    CHANNEL_MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH
                )
                ch.enableVibration(true)
                nm.createNotificationChannel(ch)
            }

            val intent = Intent(ctx, MainActivity::class.java).apply {
                putExtra("chat", chatId)
            }
            val pi = PendingIntent.getActivity(
                ctx, chatId.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            // Notification.Builder(ctx, channelId) needs API 26+; minSdk
            // is 23, so pre-O devices use the no-channel builder.
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(ctx, CHANNEL_MESSAGES)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(ctx).setPriority(Notification.PRIORITY_HIGH)
            }
            val n = builder
                .setContentTitle(if (from.isNotEmpty()) from else "Quantum Signal")
                .setContentText(if (hidePrev) "New message" else text)
                .setSmallIcon(android.R.drawable.ic_dialog_email)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            // One notification id per chat, so a new message in the same
            // chat replaces the previous one instead of stacking.
            nm.notify(chatId.hashCode(), n)
        } catch (e: Exception) {
            Dbg.log(ctx, "notify failed: ${e.message}")
        }
    }

    /**
     * Automatic encrypted cloud backup, at most every 5 minutes.
     * The UI also triggers a manual backup; this is the automatic one the
     * poller runs after a poll cycle that changed chats. Never throws.
     * The server only ever sees the opaque blob (see worker /backup).
     */
    fun backupIfNeeded(ctx: Context) {
        try {
            val key = Store.backupKey ?: return
            val now = System.currentTimeMillis()
            if (lastBackupAt != 0L && now - lastBackupAt < BACKUP_INTERVAL_MS) return
            val acct = Store.account() ?: return
            val json = Store.toJson().toString()
            val enc = Crypto.encrypt(key, json.toByteArray(Charsets.UTF_8))
            Api.backupUp(acct.user, enc.iv, enc.ct, acct.token)
            lastBackupAt = now
            Dbg.log(ctx, "backup: uploaded")
        } catch (e: Exception) {
            Dbg.log(ctx, "backup failed: ${e.message}")
        }
    }

    // ---- helpers ----

    /**
     * Peer's public key: from the cached chat if we have it, else from the
     * server (GET /user/{name}) and cached on the chat. Null on failure.
     */
    private fun peerPub(ctx: Context, name: String): JSONObject? {
        Store.chat(name)?.pub?.let { return it }
        return try {
            val pub = Api.findUser(name).getJSONObject("pub")
            Store.getOrCreateChat(name, pub)
            pub
        } catch (e: Exception) {
            Dbg.log(ctx, "poll: findUser $name failed: ${e.message}")
            null
        }
    }

    /**
     * Record one decrypt failure for [id]. After 3 failures the message
     * is acked anyway (dropped) so a corrupt message cannot wedge the
     * inbox forever; otherwise it stays unacked and is re-delivered.
     * [ackNext] is the list of ids the next poll will ack.
     */
    private fun failDecrypt(ctx: Context, id: String, ackNext: MutableList<String>) {
        val n = (retryCounts[id] ?: 0) + 1
        if (n >= 3) {
            retryCounts.remove(id)
            ackNext.add(id)
            Dbg.log(ctx, "poll: message $id undecryptable after 3 tries, dropping")
        } else {
            retryCounts[id] = n
            Dbg.log(ctx, "poll: message $id decrypt failed ($n/3), will retry")
        }
    }
}

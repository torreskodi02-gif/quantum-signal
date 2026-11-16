package app.quantumsignal

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Local chat storage for Quantum Signal (native).
 *
 * One encrypted JSON file (content = Vault.enc(json)) at
 * File(ctx.filesDir, "qs_chats.json.enc"). The JSON shape matches the web
 * app so a user can migrate between clients:
 *
 *   {"chats":[{"id","name","pub":{JWK or null},
 *               "msgs":[{"id","me":0/1,"t","ts","st","q":null,"rs":0,"exp":0}],
 *               "unread":0,"dis":0}],
 *    "user":"<my username>","token":"<token>","pub":{JWK},"priv":{JWK},
 *    "servers":["<server url>"]}
 *
 * Message status (st) — NOTE the mapping differs from the web app, which
 * uses 0/1/2 for sent/delivered/read. Here:
 *   0 = sending, 1 = sent, 2 = delivered, 3 = read, -1 = failed.
 *
 * Thread-safety: every method is @Synchronized. Mutating methods
 * (addMsg, setMsgStatus, markRead, getOrCreateChat) change in-memory state
 * ONLY — the caller must call save(ctx) to persist. setAccount() and
 * clear() persist immediately. No password is ever stored here, and
 * tokens / private keys are never logged.
 */
object Store {

    data class Account(val user: String, val token: String, val pub: JSONObject, val priv: JSONObject)

    data class Msg(
        val id: String,
        val me: Boolean,
        val t: String,
        val ts: Long,
        var st: Int,
        val q: String?,
        val rs: Int = 0,
        val exp: Long = 0
    )

    data class Chat(
        val id: String,
        var name: String,
        var pub: JSONObject?,
        val msgs: MutableList<Msg>,
        var unread: Int,
        var dis: Int
    )

    /**
     * Cloud-backup key (Crypto.bkdf output). In-memory ONLY: set at login,
     * cleared on logout (setAccount(null)) and on clear(). Never persisted.
     */
    @Volatile
    var backupKey: ByteArray? = null

    private const val FILE_NAME = "qs_chats.json.enc"

    private var acct: Account? = null
    private val chatList = mutableListOf<Chat>()
    private var storedServers: MutableList<String> = mutableListOf(Api.server)

    private fun file(ctx: Context): File = File(ctx.filesDir, FILE_NAME)

    /**
     * Loads the file if it exists (decrypts via Vault.dec, parses JSON).
     * Returns true if an account (user + token + pub + priv) was loaded.
     * If the file is missing or corrupt, leaves empty state and returns
     * false (the error is logged via Dbg, never thrown).
     */
    @Synchronized
    fun load(ctx: Context): Boolean {
        val f = file(ctx)
        if (!f.exists()) return false
        return try {
            val plain = Vault.dec(f.readText())
                ?: throw IllegalStateException("vault decrypt failed")
            fromJson(JSONObject(plain))
            acct != null
        } catch (e: Exception) {
            acct = null
            chatList.clear()
            backupKey = null
            Dbg.log(ctx, "Store: load failed: ${e.message}")
            false
        }
    }

    /** Writes the current state to the file (Vault.enc). Failures are logged, never thrown. */
    @Synchronized
    fun save(ctx: Context) {
        try {
            file(ctx).writeText(Vault.enc(toJson().toString()))
        } catch (e: Exception) {
            Dbg.log(ctx, "Store: save failed: ${e.message}")
        }
    }

    @Synchronized
    fun account(): Account? = acct

    /** Sets/clears the account AND persists immediately. Clearing the account also clears [backupKey]. */
    @Synchronized
    fun setAccount(ctx: Context, a: Account?) {
        acct = a
        if (a == null) backupKey = null
        save(ctx)
    }

    /** Snapshot copy of all chats (deep enough that mutating the result does not touch live state). */
    @Synchronized
    fun chats(): List<Chat> = chatList.map { c ->
        c.copy(
            pub = c.pub?.let { JSONObject(it.toString()) },
            msgs = c.msgs.map { it.copy() }.toMutableList()
        )
    }

    /** The live chat object, or null. Callers must not mutate it outside Store methods (except `pub`, see below). */
    @Synchronized
    fun chat(id: String): Chat? = chatList.firstOrNull { it.id == id }

    /**
     * Returns the chat for `id`, creating it (name = id) if needed.
     * `pub` may be null (peer key not yet fetched); if the chat already
     * exists without a pub and one is supplied now, it is filled in.
     * In-memory only — call save(ctx) to persist.
     */
    @Synchronized
    fun getOrCreateChat(id: String, pub: JSONObject?): Chat {
        val existing = chatList.firstOrNull { it.id == id }
        if (existing != null) {
            if (existing.pub == null && pub != null) existing.pub = pub
            return existing
        }
        val c = Chat(id = id, name = id, pub = pub, msgs = mutableListOf(), unread = 0, dis = 0)
        chatList.add(c)
        return c
    }

    /**
     * Appends a message if no message with that id already exists in the
     * chat; returns false if it was a duplicate. Does NOT touch the chat's
     * unread count — the caller owns that (e.g. the poller bumps unread
     * only for incoming messages it actually displays). In-memory only —
     * call save(ctx) to persist.
     */
    @Synchronized
    fun addMsg(chatId: String, m: Msg): Boolean {
        val c = getOrCreateChat(chatId, null)
        if (c.msgs.any { it.id == m.id }) return false
        c.msgs.add(m)
        return true
    }

    /** Sets one message's status (0 sending / 1 sent / 2 delivered / 3 read / -1 failed). In-memory only. */
    @Synchronized
    fun setMsgStatus(chatId: String, msgId: String, st: Int) {
        chatList.firstOrNull { it.id == chatId }
            ?.msgs?.firstOrNull { it.id == msgId }
            ?.let { it.st = st }
    }

    /** Clears the unread count for a chat. In-memory only. */
    @Synchronized
    fun markRead(chatId: String) {
        chatList.firstOrNull { it.id == chatId }?.let { it.unread = 0 }
    }

    /** (chatId, msg) for every unsent message (st == 0 sending or st == -1 failed). */
    @Synchronized
    fun pending(): List<Pair<String, Msg>> {
        val out = ArrayList<Pair<String, Msg>>()
        for (c in chatList) for (m in c.msgs) {
            if (m.st == 0 || m.st == -1) out.add(c.id to m.copy())
        }
        return out
    }

    /** Clears all state, clears [backupKey], and deletes the file. Persists immediately (by deleting). */
    @Synchronized
    fun clear(ctx: Context) {
        acct = null
        chatList.clear()
        backupKey = null
        try {
            file(ctx).delete()
        } catch (e: Exception) {
            Dbg.log(ctx, "Store: clear delete failed: ${e.message}")
        }
    }

    /** The full store JSON (used for cloud backup). Account fields are omitted when logged out. */
    @Synchronized
    fun toJson(): JSONObject {
        val root = JSONObject()
        val chatsArr = JSONArray()
        for (c in chatList) {
            val cj = JSONObject()
            cj.put("id", c.id)
            cj.put("name", c.name)
            cj.put("pub", c.pub ?: JSONObject.NULL)
            val msgsArr = JSONArray()
            for (m in c.msgs) {
                msgsArr.put(JSONObject().apply {
                    put("id", m.id)
                    put("me", if (m.me) 1 else 0)
                    put("t", m.t)
                    put("ts", m.ts)
                    put("st", m.st)
                    put("q", m.q ?: JSONObject.NULL)
                    put("rs", m.rs)
                    put("exp", m.exp)
                })
            }
            cj.put("msgs", msgsArr)
            cj.put("unread", c.unread)
            cj.put("dis", c.dis)
            chatsArr.put(cj)
        }
        root.put("chats", chatsArr)
        acct?.let { a ->
            root.put("user", a.user)
            root.put("token", a.token)
            root.put("pub", a.pub)
            root.put("priv", a.priv)
        }
        val srvArr = JSONArray()
        for (s in storedServers) srvArr.put(s)
        root.put("servers", srvArr)
        return root
    }

    /**
     * Replaces all in-memory state from a JSON object (used for cloud
     * restore). Does NOT persist — the caller saves. Does NOT touch
     * [backupKey] (it is derived from the password at login). Malformed
     * chats/messages are skipped, not fatal. A stored "servers" list, if
     * present, is adopted by [Api] so failover survives restarts.
     */
    @Synchronized
    fun fromJson(j: JSONObject) {
        acct = null
        chatList.clear()
        val user = j.optString("user", "")
        val token = j.optString("token", "")
        val pub = if (j.isNull("pub")) null else j.optJSONObject("pub")
        val priv = if (j.isNull("priv")) null else j.optJSONObject("priv")
        if (user.isNotEmpty() && token.isNotEmpty() && pub != null && priv != null) {
            acct = Account(user, token, pub, priv)
        }
        val srv = j.optJSONArray("servers")
        if (srv != null && srv.length() > 0) {
            val list = mutableListOf<String>()
            for (i in 0 until srv.length()) {
                val s = srv.optString(i, "")
                if (s.isNotEmpty()) list.add(s)
            }
            if (list.isNotEmpty()) {
                storedServers = list
                Api.servers = list.toMutableList()
                Api.server = list[0]
            }
        }
        val chatsArr = j.optJSONArray("chats") ?: return
        for (i in 0 until chatsArr.length()) {
            try {
                val cj = chatsArr.getJSONObject(i)
                val id = cj.optString("id", "")
                if (id.isEmpty()) continue
                val cPub = if (cj.isNull("pub")) null else cj.optJSONObject("pub")
                val chat = Chat(
                    id = id,
                    name = cj.optString("name", id),
                    pub = cPub,
                    msgs = mutableListOf(),
                    unread = cj.optInt("unread", 0),
                    dis = cj.optInt("dis", 0)
                )
                val msgsArr = cj.optJSONArray("msgs")
                if (msgsArr != null) {
                    for (k in 0 until msgsArr.length()) {
                        try {
                            val mj = msgsArr.getJSONObject(k)
                            chat.msgs.add(
                                Msg(
                                    id = mj.optString("id", ""),
                                    me = mj.optInt("me", 0) == 1,
                                    t = mj.optString("t", ""),
                                    ts = mj.optLong("ts", 0),
                                    st = mj.optInt("st", 1),
                                    q = if (mj.isNull("q")) null else mj.optString("q", ""),
                                    rs = mj.optInt("rs", 0),
                                    exp = mj.optLong("exp", 0)
                                )
                            )
                        } catch (e: Exception) {
                            // Skip one bad message, keep the rest of the chat.
                        }
                    }
                }
                chatList.add(chat)
            } catch (e: Exception) {
                // Skip one bad chat, keep the rest of the store.
            }
        }
    }
}

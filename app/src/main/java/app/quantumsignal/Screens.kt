package app.quantumsignal

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class St(val k: String, val title: String, val kind: Char, val def: Any, val opts: List<String> = emptyList())

// Settings categories. 't' = toggle (pref key k), 'c' = choice list,
// 'a' = action (handled by act()).
fun MainActivity.cats(): Map<String, List<St>> = linkedMapOf(
    "Appearance" to listOf(
        St("theme", "Theme", 'c', 0, ths.map { it.n }), St("wp", "Wallpaper", 'c', 0, wps.map { it.first }),
        St("accent", "Accent color", 'c', 0, listOf("Theme default", "Cobalt", "Coral", "Mint", "Amber", "Pink", "Violet")),
        St("font", "Font", 'c', 0, listOf("Sans", "Serif", "Mono")), St("tsize", "Text size", 'c', 1, listOf("Small", "Normal", "Large", "Huge")),
        St("bshape", "Bubble shape", 'c', 1, listOf("Sharp", "Soft", "Round")), St("dens", "Density", 'c', 1, listOf("Compact", "Cozy", "Roomy")),
        St("avshape", "Avatar shape", 'c', 0, listOf("Circle", "Rounded square")), St("avatars", "Show avatars", 't', true),
        St("ts", "Show message times", 't', true), St("h24", "24-hour time", 't', false),
        St("prev", "Show chat previews", 't', true), St("ticks", "Show sent, delivered and read status", 't', true)),
    "Privacy" to listOf(
        St("secure", "Block screenshots and screen recording", 't', true), St("lockbg", "Lock when you leave the app", 't', true),
        St("lockdelay", "Auto-lock delay", 'c', 0, listOf("Immediately", "30 seconds", "1 minute", "5 minutes")),
        St("overlay", "Block taps from overlay apps (applies on next launch)", 't', true), St("incog", "Incognito keyboard (no learning)", 't', true),
        St("hidePrev", "Hide message previews in the chat list", 't', false), St("warnInt", "Warn about device security issues", 't', true)),
    "Security" to listOf(
        St("wipeFails", "Wipe everything after too many wrong PINs", 't', false), St("failn", "Wrong PIN limit", 'c', 1, listOf("5", "10", "15")),
        St("lockout", "Slow down repeated wrong PINs", 't', true),
        St("inact", "Auto-wipe after inactivity", 'c', 0, listOf("Off", "7 days", "14 days", "30 days")),
        St("dpin", "Set duress PIN", 'a', 0), St("rmd", "Remove duress PIN", 'a', 0), St("chpin", "Change PIN", 'a', 0)),
    "Chats" to listOf(
        St("backup", "Cloud backup", 'a', 0),
        St("notif", "Notifications", 't', true),
        St("bgSvc", "Keep running in background", 't', true)),
    "Advanced" to listOf(St("dbg", "Keep debug log", 't', true)))

// Message status: 0 sending, 1 sent, 2 delivered, 3 read, -1 failed.
fun MainActivity.tick(s: Int) = when (s) { 0 -> "Sending…"; 1 -> "✓ Sent"; 2 -> "✓✓ Delivered"; 3 -> "✓✓ Read"; else -> "⚠ Not sent. Tap to retry" }
fun MainActivity.fmt(ts: Long): String = SimpleDateFormat(if (p.getBoolean("h24", false)) "HH:mm" else "h:mm a", Locale.getDefault()).format(Date(ts))
fun MainActivity.meta(m: Store.Msg) = (if (p.getBoolean("ts", true)) fmt(m.ts) + "  " else "") + (if (p.getBoolean("ticks", true)) tick(m.st) else "")

// Run f on a background thread; never let a stray exception kill the thread
// silently — log it. Call sites handle ApiErr themselves.
fun MainActivity.net(f: () -> Unit) { Thread { try { f() } catch (e: Exception) { Dbg.log(this, "net: " + e.message) } }.start() }

// Server error strings ("login", "taken", ...) into words the user can act on.
fun MainActivity.errMsg(e: Api.ApiErr) = when (e.message) {
    "login" -> "Wrong username or password"
    "taken" -> "That username is taken"
    "not found" -> "Not found"
    "bad username" -> "That username isn't valid."
    "bad login" -> "Wrong username or password"
    "too many attempts" -> "Too many attempts. Try again later."
    "slow down" -> "Slow down — try again in a moment."
    "too large" -> "That is too large to send."
    else -> e.message ?: "Something went wrong"
}

fun MainActivity.route(t: Th) {
    when {
        scr == "auth" -> authScreen(t)
        scr == "home" -> chatList(t)
        scr == "chat" -> chatView(t)
        scr == "newchat" -> newChat(t)
        scr == "settings" -> hub(t)
        scr == "account" -> accountScreen(t)
        scr == "backup" -> backupScreen(t)
        scr.startsWith("cat:") -> catScreen(t, scr.substring(4))
        scr == "user" -> userScreen(t)
        scr == "center" -> centerScreen(t)
        scr == "debug" -> debugScreen(t)
        scr == "upd" -> updScreen(t)
        else -> if (Store.account() == null) authScreen(t) else chatList(t)
    }
}

fun MainActivity.page(t: Th, title: String): Pair<LinearLayout, LinearLayout> {
    val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL; root.addView(col, FrameLayout.LayoutParams(-1, -1))
    val bar = LinearLayout(this); bar.gravity = Gravity.CENTER_VERTICAL; bar.setPadding(dp(10), dp(36), dp(16), dp(8))
    bar.addView(btn("‹", t.card, t.tx) { back() })
    val ti = tv(title, 20f, t.tx, true); ti.setPadding(dp(12), 0, 0, 0); bar.addView(ti)
    col.addView(bar)
    val box = LinearLayout(this); box.orientation = LinearLayout.VERTICAL; box.setPadding(dp(16), dp(4), dp(16), dp(16))
    val sv = ScrollView(this); sv.addView(box); col.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))
    return Pair(col, box)
}

fun MainActivity.avatar(t: Th, s: String, sz: Int = 44): View {
    val v = TextView(this); v.text = s.take(1).uppercase(); v.gravity = Gravity.CENTER; v.setTextColor(c(t.bg)); v.textSize = sz / 2.4f
    v.setTypeface(null, Typeface.BOLD)
    val g = GradientDrawable(); g.setColor(c(t.ac))
    if (p.getInt("avshape", 0) == 0) g.shape = GradientDrawable.OVAL else g.cornerRadius = dp(12).toFloat()
    v.background = g; v.layoutParams = LinearLayout.LayoutParams(dp(sz), dp(sz)); return v
}

fun MainActivity.nav(t: Th, title: String, sub: String, f: () -> Unit): View {
    val cd = card(t, tv(title, 16f, t.tx, true), tv(sub, 12f, t.sub)); cd.setOnClickListener { f() }; return cd
}

fun MainActivity.chatRow(t: Th, name: String, prev: String, time: String, tk: String, f: () -> Unit): View {
    val r = LinearLayout(this); r.gravity = Gravity.CENTER_VERTICAL; r.setPadding(dp(4), dp(10), dp(4), dp(10))
    if (p.getBoolean("avatars", true)) r.addView(avatar(t, name.trimStart('@')))
    val m = LinearLayout(this); m.orientation = LinearLayout.VERTICAL; m.setPadding(dp(12), 0, dp(8), 0)
    m.addView(tv(name, 16f, t.tx, true)); if (p.getBoolean("prev", true)) m.addView(tv(prev, 13f, t.sub))
    r.addView(m, LinearLayout.LayoutParams(0, -2, 1f)); r.addView(tv("$time $tk".trim(), 12f, t.sub))
    r.setOnClickListener { f() }; return r
}

fun MainActivity.msgMenu(chatId: String, m: Store.Msg) {
    AlertDialog.Builder(this).setItems(arrayOf("Copy", "Delete")) { _, i ->
        if (i == 0) { (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("m", m.t)); toast("Copied") }
        else { Store.chat(chatId)?.msgs?.removeAll { it.id == m.id }; Store.save(this); render() }
    }.show()
}

// ---- Auth: log in or create an account (username + password, E2E keys) ----

fun MainActivity.authScreen(t: Th) {
    val l = center(t, "Quantum Signal", "Log in or create an account.")
    val u = EditText(this).apply {
        hint = "username"; setTextColor(c(t.tx)); setHintTextColor(c(t.sub))
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        if (p.getBoolean("incog", true)) imeOptions = 0x1000000
    }
    val pw = EditText(this).apply {
        hint = "password"; setTextColor(c(t.tx)); setHintTextColor(c(t.sub))
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        if (p.getBoolean("incog", true)) imeOptions = 0x1000000
    }
    val m = tv("", 13f, "#FF6B57")
    l.addView(u, lp(16)); l.addView(pw, lp(8)); l.addView(m)
    var loginB: Button? = null
    var regB: Button? = null
    fun unlockButtons() { authBusy = false; loginB?.isEnabled = true; regB?.isEnabled = true }

    fun doAuth(isNew: Boolean) {
        if (authBusy) return
        val name = u.text.toString().trim().lowercase()
        val pass = pw.text.toString()
        if (!Regex("^[a-z0-9_]{3,20}$").matches(name)) { m.text = "Usernames are 3–20 lowercase letters, numbers and underscores."; return }
        if (isNew) {
            if (pass.length < 12) { m.text = "Use at least 12 characters."; return }
            val weak = setOf("password", "password123", "123456789012", "qwerty123456", "letmein123456", "quantum123456", "signal123456", "1234567890ab", "iloveyou12345")
            if (pass.lowercase() in weak || pass.lowercase() == name) { m.text = "That password is too common. Pick a stronger one."; return }
        } else if (pass.isEmpty()) { m.text = "Enter your password."; return }
        authBusy = true; loginB?.isEnabled = false; regB?.isEnabled = false; m.text = "Please wait…"
        net {
            try {
                if (isNew) {
                    val idn = Crypto.generateIdentity()
                    val kdf = Crypto.kdf(name, pass)
                    val vct = Crypto.encryptVault(kdf.key, idn.priv.toString())
                    val vault = JSONObject().put("iv", vct.iv).put("ct", vct.ct)
                    val res = Api.register(name, idn.pub, kdf.authHex, vault)
                    Store.setAccount(this, Store.Account(user = name, token = res.getString("token"), pub = idn.pub, priv = idn.priv))
                    Store.backupKey = Crypto.bkdf(name, pass)
                    runOnUiThread { unlockButtons(); Poller.start(this); scr = "home"; render() }
                } else {
                    val kdf = Crypto.kdf(name, pass)
                    val res = Api.login(name, kdf.authHex)
                    val token = res.getString("token")
                    val pub = res.getJSONObject("pub"); val vault = res.getJSONObject("vault")
                    val priv = JSONObject(Crypto.decryptVault(kdf.key, vault.getString("iv"), vault.getString("ct")))
                    Store.setAccount(this, Store.Account(user = name, token = token, pub = pub, priv = priv))
                    Store.backupKey = Crypto.bkdf(name, pass)
                    // Restore the cloud backup, but only when this phone has
                    // no chats of its own — local chats always win.
                    if (Store.chats().isEmpty()) {
                        try {
                            val b = Api.backupDown(name, token)
                            val plain = Crypto.decrypt(Store.backupKey!!, b.getString("iv"), b.getString("data"))
                            Store.fromJson(JSONObject(String(plain, Charsets.UTF_8))); Store.save(this)
                        } catch (e: Exception) { Dbg.log(this, "backup restore skipped: ${e.message}") }
                    }
                    runOnUiThread {
                        unlockButtons(); Poller.start(this)
                        Fcm.storedToken(this)?.let { Fcm.onToken(this, it) }
                        scr = "home"; render()
                    }
                }
            } catch (e: Api.ApiErr) {
                runOnUiThread { unlockButtons(); m.text = errMsg(e) }
            } catch (e: Exception) {
                runOnUiThread { unlockButtons(); m.text = "Couldn't reach the server. Check your connection." }
            }
        }
    }
    loginB = btn("Log in", t.ac, t.bg) { doAuth(false) }
    regB = btn("Create account", t.card, t.tx) { doAuth(true) }
    l.addView(loginB, lp(12)); l.addView(regB, lp(8))
    l.addView(tv("Your password never leaves this phone in readable form — only a one-way check value is sent to the server.", 12f, t.sub), lp(12))
}

// ---- Chat list (home) ----

fun MainActivity.chatList(t: Th) {
    val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL
    root.addView(col, FrameLayout.LayoutParams(-1, -1))
    val me = Store.account()
    val top = LinearLayout(this); top.gravity = Gravity.CENTER_VERTICAL; top.setPadding(dp(16), dp(38), dp(12), dp(6))
    val av = avatar(t, me?.user ?: "Q", 40); av.setOnClickListener { scr = "settings"; render() }
    top.addView(av)
    val ti = tv("Quantum Signal", 22f, t.tx, true); ti.setPadding(dp(12), 0, 0, 0); top.addView(ti, LinearLayout.LayoutParams(0, -2, 1f))
    top.addView(btn("＋", t.card, t.tx) { scr = "newchat"; render() }); top.addView(btn("⚙", t.card, t.tx) { scr = "settings"; render() })
    col.addView(top)
    val box = LinearLayout(this); box.orientation = LinearLayout.VERTICAL; box.setPadding(dp(16), dp(4), dp(16), dp(16))
    val sv = ScrollView(this); sv.addView(box); col.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))
    // Store.chats() returns snapshot copies — read-only here.
    val chats = Store.chats().sortedByDescending { it.msgs.maxOfOrNull { m -> m.ts } ?: 0L }
    if (chats.isEmpty()) box.addView(card(t, tv("No chats yet — tap ＋ to start one.", 14f, t.tx)), lp(8))
    chats.forEach { ch ->
        val last = ch.msgs.maxByOrNull { it.ts }
        val prev = when { last == null -> "No messages yet"; p.getBoolean("hidePrev", false) -> "Message"; else -> last.t }
        val tk = if (ch.unread > 0) "• ${ch.unread}" else if (last == null || !p.getBoolean("ticks", true)) "" else tick(last.st).substringBefore(" ")
        box.addView(chatRow(t, "@" + ch.id, prev, if (last == null) "" else fmt(last.ts), tk) { curChat = ch.id; scr = "chat"; render() }, lp(0))
    }
    // Re-render every 2s while on the chat list so messages delivered by
    // the Poller appear without the user leaving the screen.
    Handler(Looper.getMainLooper()).postDelayed({ if (scr == "home" && unlocked) render() }, 2000)
}

// ---- One chat: bubbles, send bar, read receipts ----

fun MainActivity.chatView(t: Th) {
    val id = curChat ?: run { scr = "home"; render(); return }
    val chat = Store.getOrCreateChat(id, Store.chat(id)?.pub)
    val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL; root.addView(col, FrameLayout.LayoutParams(-1, -1))
    val bar = LinearLayout(this); bar.gravity = Gravity.CENTER_VERTICAL; bar.setPadding(dp(10), dp(36), dp(16), dp(8))
    bar.addView(btn("‹", t.card, t.tx) { back() })
    val ti = tv("@$id", 20f, t.tx, true); ti.setPadding(dp(12), 0, 0, 0); bar.addView(ti, LinearLayout.LayoutParams(0, -2, 1f))
    bar.addView(btn("📞", t.card, t.tx) { toast("Voice calls coming soon") })
    bar.addView(btn("📹", t.card, t.tx) { toast("Video calls coming soon") })
    col.addView(bar)
    val box = LinearLayout(this); box.orientation = LinearLayout.VERTICAL; box.setPadding(dp(16), dp(4), dp(16), dp(16))
    val sv = ScrollView(this); sv.addView(box); col.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))
    val rad = listOf(4, 14, 24)[p.getInt("bshape", 1).coerceIn(0, 2)]
    val msgs = chat.msgs.sortedBy { it.ts }
    if (msgs.isEmpty()) box.addView(tv("Only you and @$id can read these messages.", 13f, t.sub), lp(8))
    msgs.forEach { m ->
        val b = tv(m.t, 15f, if (m.me) t.bg else t.tx); b.setPadding(dp(14), dp(9), dp(14), dp(9))
        val g = GradientDrawable(); g.setColor(c(if (m.me) t.ac else t.card)); g.cornerRadius = dp(rad).toFloat(); b.background = g
        b.setOnLongClickListener { msgMenu(id, m); true }
        val w = LinearLayout(this); w.orientation = LinearLayout.VERTICAL; w.gravity = if (m.me) Gravity.END else Gravity.START
        w.addView(b, LinearLayout.LayoutParams(-2, -2))
        w.addView(tv(meta(m), 11f, t.sub))
        if (m.me && m.st == -1) w.setOnClickListener { retrySend(chat, m) }
        box.addView(w, lp(6))
    }
    val bar2 = LinearLayout(this); bar2.gravity = Gravity.CENTER_VERTICAL; bar2.setPadding(dp(10), dp(6), dp(10), dp(14))
    val e = EditText(this); e.hint = "Message"; e.setTextColor(c(t.tx)); e.setHintTextColor(c(t.sub)); e.maxLines = 4
    var ty = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
    if (p.getBoolean("incog", true)) { ty = ty or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS; e.imeOptions = 0x1000000 }
    e.inputType = ty
    fun down() { sv.post { sv.fullScroll(View.FOCUS_DOWN) } }
    bar2.addView(e, LinearLayout.LayoutParams(0, -2, 1f))
    bar2.addView(btn("Send", t.ac, t.bg) {
        val s = e.text.toString().trim()
        if (s.isNotEmpty()) { e.setText(""); sendText(chat, s) }
    })
    col.addView(bar2); down()
    // Opening the chat marks it read and tells the sender (receipts on).
    if (chat.unread > 0 && p.getBoolean("receipts", true)) {
        val ids = chat.msgs.filter { !it.me }.map { it.id }
        Store.markRead(id); Store.save(this)
        net { try { Poller.sendReceipt(this, id, "r", ids) } catch (e: Exception) { Dbg.log(this, "receipt failed: ${e.message}") } }
    }
    // Catch up in case anything arrived while this screen was closed.
    net { if (Poller.pollOnce(this)) runOnUiThread { if (scr == "chat" && curChat == id) render() } }
}

// Queue a message locally, then encrypt + send it on a background thread.
// The same id is reused on retry so the peer dedupes it.
fun MainActivity.sendText(chat: Store.Chat, text: String) {
    val m = Store.Msg(id = UUID.randomUUID().toString(), me = true, t = text, ts = System.currentTimeMillis(), st = 0, q = null)
    Store.addMsg(chat.id, m); Store.save(this); render()
    deliver(chat, m)
}

fun MainActivity.retrySend(chat: Store.Chat, m: Store.Msg) { deliver(chat, m); render() }

fun MainActivity.deliver(chat: Store.Chat, m: Store.Msg) {
    val acc = Store.account() ?: return
    Store.setMsgStatus(chat.id, m.id, 0); Store.save(this)
    net {
        try {
            val pub = chat.pub ?: run { val p = Api.findUser(chat.id).getJSONObject("pub"); chat.pub = p; Store.save(this); p }
            val ct = Crypto.encryptMsg(pub, acc.priv, JSONObject().put("k", "m").put("id", m.id).put("t", m.t).toString())
            Api.send(acc.user, chat.id, ct.ct, ct.iv, acc.token)
            Store.setMsgStatus(chat.id, m.id, 1); Store.save(this)
            runOnUiThread { if (scr == "chat" && curChat == chat.id) render() }
        } catch (e: Exception) {
            Store.setMsgStatus(chat.id, m.id, -1); Store.save(this)
            runOnUiThread { if (scr == "chat" && curChat == chat.id) render() }
            Dbg.log(this, "send failed: ${e.message}")
        }
    }
}

// ---- New chat: look a user up by name ----

fun MainActivity.newChat(t: Th) {
    val (_, box) = page(t, "New chat")
    val e = EditText(this).apply {
        hint = "username"; setTextColor(c(t.tx)); setHintTextColor(c(t.sub))
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        if (p.getBoolean("incog", true)) imeOptions = 0x1000000
    }
    val m = tv("Usernames are 3–20 lowercase letters, numbers and underscores.", 13f, t.sub)
    var searching = false
    val fb = btn("Find", t.ac, t.bg) {
        if (searching) return@btn
        val name = e.text.toString().trim().lowercase()
        if (!Regex("^[a-z0-9_]{3,20}$").matches(name)) { m.text = "That username isn't valid."; return@btn }
        searching = true; m.text = "Looking up @$name…"
        net {
            try {
                val pub = Api.findUser(name).getJSONObject("pub")
                Store.getOrCreateChat(name, pub); Store.save(this)
                runOnUiThread { curChat = name; scr = "chat"; render() }
            } catch (e: Api.ApiErr) {
                runOnUiThread { searching = false; m.text = if (e.message == "not found") "No user called @$name" else errMsg(e) }
            } catch (e: Exception) {
                runOnUiThread { searching = false; m.text = "Couldn't reach the server. Check your connection." }
            }
        }
    }
    box.addView(card(t, e, fb, m), lp(4))
}

// ---- Settings ----

fun MainActivity.hub(t: Th) {
    val (_, box) = page(t, "Settings")
    val un = Store.account()?.user ?: ""
    val pc = LinearLayout(this); pc.gravity = Gravity.CENTER_VERTICAL
    pc.addView(avatar(t, if (un.isEmpty()) "?" else un, 56))
    val tx = LinearLayout(this); tx.orientation = LinearLayout.VERTICAL; tx.setPadding(dp(14), 0, 0, 0)
    tx.addView(tv(if (un.isEmpty()) "Not logged in" else "@$un", 18f, t.tx, true)); tx.addView(tv("Profile and username", 13f, t.sub)); pc.addView(tx)
    val pcard = card(t, pc); pcard.setOnClickListener { scr = "account"; render() }; box.addView(pcard, lp(4))
    cats().forEach { (k, v) -> box.addView(nav(t, k, "${v.size} options") { scr = "cat:$k"; render() }, lp(10)) }
    box.addView(nav(t, "Cloud backup", "Encrypted backup of your chats") { scr = "backup"; render() }, lp(10))
    box.addView(nav(t, "Diagnostics", "Push status, background service and checks") { scr = "debug"; render() }, lp(10))
    box.addView(nav(t, "Security Center", "17 live device and app checks") { scr = "center"; render() }, lp(10))
    box.addView(nav(t, "Debug log", "Errors and crash reports you can copy") { scr = "debug"; render() }, lp(10))
    box.addView(nav(t, "About and updates", "Version ${Upd.name(this)}") { scr = "upd"; render() }, lp(10))
}

fun MainActivity.act(k: String) {
    when (k) {
        "chpin" -> { p.edit().remove("h").apply(); render() }
        "rmd" -> { p.edit().remove("dh").apply(); toast("Duress PIN removed") }
        "backup" -> { scr = "backup"; render() }
        "dpin" -> {
            val e = EditText(this); e.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD; e.hint = "Duress PIN (6+ digits)"
            AlertDialog.Builder(this).setTitle("Duress PIN").setMessage("Entering this PIN on the lock screen silently wipes the app.").setView(e)
                .setPositiveButton("Save") { _, _ ->
                    val x = e.text.toString(); val salt = android.util.Base64.decode(p.getString("s", ""), android.util.Base64.NO_WRAP)
                    if (x.length < 6 || hash(x, salt) == p.getString("h", "")) toast("Use 6+ digits that differ from your PIN")
                    else { p.edit().putString("dh", hash(x, salt)).apply(); toast("Duress PIN set") }
                }.setNegativeButton("Cancel", null).show()
        }
    }
}

fun MainActivity.catScreen(t: Th, name: String) {
    val (_, box) = page(t, name)
    (cats()[name] ?: emptyList()).forEach { s ->
        when (s.kind) {
            't' -> box.addView(card(t, sw(t, s.title, s.k, s.def as Boolean) { v ->
                when (s.k) {
                    "secure" -> applySecure()
                    "bgSvc" -> if (v) Poller.start(this) else Poller.stop(this)
                }
            }), lp(8))
            'c' -> {
                val cur = p.getInt(s.k, s.def as Int).coerceIn(0, s.opts.size - 1)
                val cd = card(t, tv(s.title, 15f, t.tx), tv(s.opts[cur], 13f, t.ac, true))
                cd.setOnClickListener { AlertDialog.Builder(this).setTitle(s.title).setItems(s.opts.toTypedArray()) { _, i -> p.edit().putInt(s.k, i).apply(); render() }.show() }
                box.addView(cd, lp(8))
            }
            else -> box.addView(btn(s.title, t.card, t.tx) { act(s.k) }, lp(8))
        }
    }
}

// Account screen: log out here, or log out everywhere else.
fun MainActivity.accountScreen(t: Th) {
    val (_, box) = page(t, "Account")
    val un = Store.account()?.user ?: ""
    box.addView(card(t, avatar(t, if (un.isEmpty()) "?" else un, 72),
        tv(if (un.isEmpty()) "Not logged in" else "@$un", 18f, t.tx, true)), lp(4))
    box.addView(btn("Log out of all other devices", t.card, t.tx) {
        val a = Store.account() ?: return@btn
        net {
            try {
                val n = Api.logoutAll(a.user, a.token).optInt("removed", 0)
                runOnUiThread { toast("Logged out of $n other device${if (n == 1) "" else "s"}") }
            } catch (e: Api.ApiErr) { runOnUiThread { toast(errMsg(e)) } }
            catch (e: Exception) { runOnUiThread { toast("Couldn't reach the server.") } }
        }
    }, lp(10))
    box.addView(btn("Log out", t.card, t.tx) {
        val a = Store.account() ?: return@btn
        net {
            try { Api.logout(a.user, a.token) } catch (e: Exception) { Dbg.log(this, "logout: ${e.message}") }
            Store.clear(this); Poller.stop(this); Store.backupKey = null
            runOnUiThread { curChat = null; scr = "auth"; render() }
        }
    }, lp(8))
    box.addView(tv("Logging out deletes your chats from this phone. Turn on Cloud backup first if you want them back later.", 12f, t.sub), lp(8))
}

// ---- Cloud backup: encrypted blob on the server, password-derived key ----

fun MainActivity.backupScreen(t: Th) {
    val (_, box) = page(t, "Cloud backup")
    val last = p.getLong("lastBackup", 0L)
    val st = tv(if (last == 0L) "Last backup: never" else "Last backup: " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(last)), 14f, t.tx)
    box.addView(card(t, st, tv("Chats are encrypted on this phone before upload. The server can't read them.", 13f, t.sub)), lp(4))
    box.addView(btn("Back up now", t.ac, t.bg) {
        val a = Store.account() ?: return@btn
        val key = Store.backupKey ?: run { toast("Log in again to enable backup"); return@btn }
        net {
            try {
                val ct = Crypto.encrypt(key, Store.toJson().toString().toByteArray(Charsets.UTF_8))
                Api.backupUp(a.user, ct.iv, ct.ct, a.token)
                p.edit().putLong("lastBackup", System.currentTimeMillis()).apply()
                runOnUiThread { toast("Backed up"); render() }
            } catch (e: Api.ApiErr) { runOnUiThread { toast(errMsg(e)) } }
            catch (e: Exception) { runOnUiThread { toast("Backup failed. Check your connection.") } }
        }
    }, lp(10))
    box.addView(btn("Restore from backup", t.card, t.tx) {
        val a = Store.account() ?: return@btn
        val e = EditText(this); e.hint = "Account password"; e.setTextColor(c(t.tx)); e.setHintTextColor(c(t.sub))
        e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        AlertDialog.Builder(this).setTitle("Restore from backup").setMessage("Enter your account password to decrypt the backup.").setView(e)
            .setPositiveButton("Restore") { _, _ ->
                val pw = e.text.toString()
                net {
                    try {
                        val b = Api.backupDown(a.user, a.token)
                        val plain = Crypto.decrypt(Crypto.bkdf(a.user, pw), b.getString("iv"), b.getString("data"))
                        Store.fromJson(JSONObject(String(plain, Charsets.UTF_8))); Store.save(this)
                        runOnUiThread { toast("Restored"); scr = "home"; render() }
                    } catch (e: Api.ApiErr) { runOnUiThread { toast(if (e.message == "not found") "No backup found" else errMsg(e)) } }
                    catch (e: Exception) { runOnUiThread { toast("Restore failed — wrong password?") } }
                }
            }.setNegativeButton("Cancel", null).show()
    }, lp(8))
    box.addView(btn("Delete backup", t.card, t.tx) {
        val a = Store.account() ?: return@btn
        net {
            try {
                Api.backupDel(a.user, a.token); p.edit().remove("lastBackup").apply()
                runOnUiThread { toast("Backup deleted"); render() }
            } catch (e: Api.ApiErr) { runOnUiThread { toast(errMsg(e)) } }
            catch (e: Exception) { runOnUiThread { toast("Couldn't reach the server.") } }
        }
    }, lp(8))
}

fun MainActivity.userScreen(t: Th) {
    val (_, box) = page(t, "Username")
    val cur = Store.account()?.user ?: ""
    box.addView(card(t, avatar(t, if (cur.isEmpty()) "?" else cur, 72),
        tv(if (cur.isEmpty()) "Not logged in" else "@$cur", 18f, t.tx, true),
        tv("Your username is set when you log in.", 13f, t.sub)), lp(4))
    box.addView(btn("Account and log out", t.card, t.tx) { scr = "account"; render() }, lp(12))
}

fun MainActivity.centerScreen(t: Th) {
    val (_, box) = page(t, "Security Center")
    val r = Chk.run(this, p); val ok = r.count { it.second }
    box.addView(card(t, tv("$ok of ${r.size} checks passing", 20f, t.tx, true), tv("These run every time you open the app. A ✗ is worth a look, not always a problem.", 12f, t.sub)), lp(4))
    box.addView(card(t, *r.map { x -> tv((if (x.second) "✓  " else "✗  ") + x.first + (if (!x.second && x.third.isNotEmpty()) "\n     " + x.third else ""), 14f, if (x.second) t.tx else "#FF6B57") }.toTypedArray()), lp(10))
    box.addView(btn("Run checks again", t.ac, t.bg) { render() }, lp(10))
}

fun MainActivity.debugScreen(t: Th) {
    val (_, box) = page(t, "Debug log")
    val push = "Push notifications: " + (if (Fcm.storedToken(this) != null) "registered" else "not registered — needs Firebase (google-services.json)")
    val bgs = "Background service: " + (if (p.getBoolean("bgSvc", true)) "on" else "off")
    box.addView(card(t, tv(push, 13f, t.tx), tv(bgs, 13f, t.tx)), lp(4))
    val txt = Dbg.read(this).ifEmpty { "No events yet." }
    val dv = tv("Version ${Upd.name(this)} · Android ${Build.VERSION.RELEASE} · ${Build.MODEL}\n\n" + txt.takeLast(6000), 11f, "#39FF6A")
    dv.typeface = Typeface.MONOSPACE; dv.setTextIsSelectable(true)
    box.addView(card(t, dv), lp(4))
    box.addView(btn("Copy log", t.ac, t.bg) { (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("log", txt)); toast("Copied. Paste it to your developer.") }, lp(10))
    box.addView(btn("Clear log", t.card, t.tx) { Dbg.clear(this); render() }, lp(8))
}

fun MainActivity.updScreen(t: Th) {
    val (_, box) = page(t, "About and updates")
    val cur = Upd.cur(this); val nv = p.getInt("newv", 0); val url = p.getString("newurl", null)
    val vt = tv("Version ${Upd.name(this)}" + (if (nv > cur) " · new build $nv available" else ""), 14f, t.sub)
    val v = ArrayList<View>(); v.add(vt)
    if (nv > cur && url != null) v.add(btn(if (busy) "Updating..." else "Update now", t.ac, t.bg) {
        if (busy) return@btn
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            toast("Allow installs from Quantum Signal, then tap Update now again")
            try { startActivity(Intent("android.settings.MANAGE_UNKNOWN_APP_SOURCES", Uri.parse("package:$packageName"))) } catch (e: Exception) {}
            return@btn
        }
        busy = true
        Upd.install(this, url, { pct -> vt.text = "Downloading $pct%" }, { e ->
            busy = false
            if (e != null) { toast(e); render() } else { vt.text = "Downloaded. Confirm the install on the next screen." }
        })
    })
    v.add(btn("Check for updates", t.card, t.tx) {
        Upd.check(this) { code, u, err ->
            if (err == null) p.edit().putInt("newv", code).putString("newurl", u).apply()
            toast(err ?: (if (code > Upd.cur(this)) "Update available" else "You’re on the latest version")); render()
        }
    })
    box.addView(card(t, *v.toTypedArray()), lp(4))
}

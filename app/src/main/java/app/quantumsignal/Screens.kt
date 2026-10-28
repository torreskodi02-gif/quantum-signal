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
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class St(val k: String, val title: String, val kind: Char, val def: Any, val opts: List<String> = emptyList())

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
        St("disap", "Disappearing notes", 'c', 0, listOf("Off", "1 hour", "1 day", "7 days")), St("clearNotes", "Delete all notes", 'a', 0)),
    "Advanced" to listOf(St("dbg", "Keep debug log", 't', true)))

fun MainActivity.tick(s: Int) = when (s) { 0 -> "Sending…"; 1 -> "✓ Sent"; 2 -> "✓✓ Delivered"; 3 -> "✓✓ Read"; else -> "⚠ Not sent. Tap to retry" }
fun MainActivity.fmt(ts: Long): String = SimpleDateFormat(if (p.getBoolean("h24", false)) "HH:mm" else "h:mm a", Locale.getDefault()).format(Date(ts))
fun MainActivity.meta(m: Msg) = (if (p.getBoolean("ts", true)) fmt(m.id) + "  " else "") + (if (p.getBoolean("ticks", true)) tick(m.st) else "")

fun MainActivity.route(t: Th) {
    when {
        scr == "chat" -> chat(t)
        scr == "settings" -> hub(t)
        scr.startsWith("cat:") -> catScreen(t, scr.substring(4))
        scr == "user" -> userScreen(t)
        scr == "center" -> centerScreen(t)
        scr == "debug" -> debugScreen(t)
        scr == "upd" -> updScreen(t)
        else -> home(t)
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
    if (p.getBoolean("avatars", true)) r.addView(avatar(t, name))
    val m = LinearLayout(this); m.orientation = LinearLayout.VERTICAL; m.setPadding(dp(12), 0, dp(8), 0)
    m.addView(tv(name, 16f, t.tx, true)); if (p.getBoolean("prev", true)) m.addView(tv(prev, 13f, t.sub))
    r.addView(m, LinearLayout.LayoutParams(0, -2, 1f)); r.addView(tv("$time $tk".trim(), 12f, t.sub))
    r.setOnClickListener { f() }; return r
}

fun MainActivity.msgMenu(m: Msg) {
    AlertDialog.Builder(this).setItems(arrayOf("Copy", "Delete")) { _, i ->
        if (i == 0) { (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("m", m.text)); toast("Copied") }
        else { Msgs.save(this, Msgs.load(this).filter { it.id != m.id }); render() }
    }.show()
}

fun MainActivity.chat(t: Th) {
    val (col, box) = page(t, "Note to Self")
    val ttl = listOf(0L, 3600000L, 86400000L, 604800000L)[p.getInt("disap", 0).coerceIn(0, 3)]
    val now = System.currentTimeMillis()
    val rad = listOf(4, 14, 24)[p.getInt("bshape", 1).coerceIn(0, 2)]
    fun add(m: Msg): TextView {
        val b = tv(m.text, 15f, t.bg); b.setPadding(dp(14), dp(9), dp(14), dp(9))
        val g = GradientDrawable(); g.setColor(c(t.ac)); g.cornerRadius = dp(rad).toFloat(); b.background = g
        b.setOnLongClickListener { msgMenu(m); true }
        val w = LinearLayout(this); w.orientation = LinearLayout.VERTICAL; w.gravity = Gravity.END
        w.addView(b, LinearLayout.LayoutParams(-2, -2))
        val mt = tv(meta(m), 11f, t.sub); w.addView(mt)
        if (m.st == 4) w.setOnClickListener { Msgs.save(this, Msgs.load(this).map { if (it.id == m.id) Msg(it.id, it.text, 1) else it }); render() }
        box.addView(w, lp(6)); return mt
    }
    val all = Msgs.load(this).filter { ttl == 0L || now - it.id < ttl }
    if (all.isEmpty()) box.addView(tv("Only you can see this chat. Notes are encrypted on this phone.", 13f, t.sub), lp(8))
    all.forEach { add(it) }
    val bar = LinearLayout(this); bar.gravity = Gravity.CENTER_VERTICAL; bar.setPadding(dp(10), dp(6), dp(10), dp(14))
    val e = EditText(this); e.hint = "Message"; e.setTextColor(c(t.tx)); e.setHintTextColor(c(t.sub)); e.maxLines = 4
    var ty = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
    if (p.getBoolean("incog", true)) { ty = ty or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS; e.imeOptions = 0x1000000 }
    e.inputType = ty
    val sv = box.parent as ScrollView
    fun down() { sv.post { sv.fullScroll(View.FOCUS_DOWN) } }
    bar.addView(e, LinearLayout.LayoutParams(0, -2, 1f))
    bar.addView(btn("Send", t.ac, t.bg) {
        val s = e.text.toString().trim()
        if (s.isNotEmpty()) {
            val m = Msg(System.currentTimeMillis(), s, 0)
            Msgs.save(this, Msgs.load(this) + m); e.setText(""); val mt = add(m); down()
            Handler(Looper.getMainLooper()).postDelayed({
                Msgs.save(this, Msgs.load(this).map { if (it.id == m.id) Msg(it.id, it.text, 1) else it }); mt.text = meta(Msg(m.id, m.text, 1))
            }, 500)
        }
    })
    col.addView(bar); down()
}

fun MainActivity.hub(t: Th) {
    val (_, box) = page(t, "Settings")
    val un = Vault.dec(p.getString("user", "") ?: "") ?: ""
    val pc = LinearLayout(this); pc.gravity = Gravity.CENTER_VERTICAL
    pc.addView(avatar(t, if (un.isEmpty()) "?" else un, 56))
    val tx = LinearLayout(this); tx.orientation = LinearLayout.VERTICAL; tx.setPadding(dp(14), 0, 0, 0)
    tx.addView(tv(if (un.isEmpty()) "Choose a username" else "@$un", 18f, t.tx, true)); tx.addView(tv("Profile and username", 13f, t.sub)); pc.addView(tx)
    val pcard = card(t, pc); pcard.setOnClickListener { scr = "user"; render() }; box.addView(pcard, lp(4))
    cats().forEach { (k, v) -> box.addView(nav(t, k, "${v.size} options") { scr = "cat:$k"; render() }, lp(10)) }
    box.addView(nav(t, "Security Center", "17 live device and app checks") { scr = "center"; render() }, lp(10))
    box.addView(nav(t, "Debug log", "Errors and crash reports you can copy") { scr = "debug"; render() }, lp(10))
    box.addView(nav(t, "About and updates", "Version ${Upd.name(this)}") { scr = "upd"; render() }, lp(10))
}

fun MainActivity.act(k: String) {
    when (k) {
        "chpin" -> { p.edit().remove("h").apply(); render() }
        "rmd" -> { p.edit().remove("dh").apply(); toast("Duress PIN removed") }
        "clearNotes" -> { Msgs.save(this, emptyList()); toast("Notes deleted") }
        "dpin" -> {
            val e = EditText(this); e.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD; e.hint = "Duress PIN (6+ digits)"
            AlertDialog.Builder(this).setTitle("Duress PIN").setMessage("Entering this PIN on the lock screen silently wipes the app.").setView(e)
                .setPositiveButton("Save") { _, _ ->
                    val x = e.text.toString(); val salt = Base64.decode(p.getString("s", ""), Base64.NO_WRAP)
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
            't' -> box.addView(card(t, sw(t, s.title, s.k, s.def as Boolean) { if (s.k == "secure") applySecure() }), lp(8))
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

fun MainActivity.userScreen(t: Th) {
    val (_, box) = page(t, "Username")
    val cur = Vault.dec(p.getString("user", "") ?: "") ?: ""
    val e = EditText(this); e.setText(cur); e.hint = "username"; e.setTextColor(c(t.tx)); e.setHintTextColor(c(t.sub))
    e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    val m = tv("3 to 20 characters: lowercase letters, numbers and underscores.", 13f, t.sub)
    box.addView(card(t, avatar(t, if (cur.isEmpty()) "?" else cur, 72), e, m), lp(4))
    box.addView(btn("Save username", t.ac, t.bg) {
        val x = e.text.toString().trim().lowercase()
        if (!Regex("^[a-z0-9_]{3,20}$").matches(x)) m.text = "That username isn’t valid." else { p.edit().putString("user", Vault.enc(x)).apply(); toast("Saved"); back() }
    }, lp(12))
    box.addView(tv("Stored encrypted on this phone. Uniqueness is checked against the server once accounts launch.", 12f, t.sub), lp(8))
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

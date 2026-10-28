package app.quantumsignal

import android.app.Activity
import android.app.ActivityManager
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class Th(val n: String, val bg: String, val card: String, val ac: String, val tx: String, val sub: String)

class MainActivity : Activity() {
    internal val ths = listOf(
        Th("Cobalt", "#0F1240", "#1A1F5E", "#7C84FF", "#F2F3FF", "#A9AEE8"),
        Th("Midnight", "#0A0D12", "#141A22", "#7DD3FC", "#FFFFFF", "#9AA4B2"),
        Th("Ember", "#1C0F0C", "#2B1914", "#FF7A59", "#FFF2EC", "#D9A596"),
        Th("Forest", "#0C1A14", "#14281E", "#4ADE80", "#EAFBF1", "#8FBFA2"),
        Th("Paper", "#F3F1FA", "#FFFFFF", "#3B30F0", "#14123A", "#55527A"),
        Th("Mono", "#000000", "#111111", "#FFFFFF", "#FFFFFF", "#8A8A8A"))
    internal val wps = listOf(
        "Plain" to intArrayOf(),
        "Aurora" to intArrayOf(0xFF1B2B6B.toInt(), 0xFF0F6E6E.toInt()),
        "Sunset" to intArrayOf(0xFF3B1C5A.toInt(), 0xFFB5435A.toInt()),
        "Deep sea" to intArrayOf(0xFF021B3A.toInt(), 0xFF0B5C7A.toInt()),
        "Graphite" to intArrayOf(0xFF111111.toInt(), 0xFF3A3A3A.toInt()),
        "Dawn" to intArrayOf(0xFFF7D9C4.toInt(), 0xFFC9B6F2.toInt()))
    internal val p by lazy { getSharedPreferences("qs", MODE_PRIVATE) }
    internal lateinit var root: FrameLayout
    internal var unlocked = false
    internal var tab = 0
    internal var busy = false
    internal var scr = "home"
    internal var bgAt = 0L
    internal val accents = listOf("#3B30F0", "#FF6B57", "#4ADE80", "#F5A524", "#F472B6", "#A78BFA")

    internal fun c(s: String) = Color.parseColor(s)
    internal fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    internal fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    internal fun lp(m: Int = 8): LinearLayout.LayoutParams { val q = LinearLayout.LayoutParams(-1, -2); q.setMargins(0, dp(m), 0, 0); return q }
    internal fun th(): Th {
        val b = ths[p.getInt("theme", 0).coerceIn(0, ths.size - 1)]; val a = p.getInt("accent", 0)
        return if (a <= 0 || a > accents.size) b else Th(b.n, b.bg, b.card, accents[a - 1], b.tx, b.sub)
    }

    internal fun tv(s: String, sz: Float, col: String, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = sz * listOf(0.9f, 1f, 1.15f, 1.3f)[p.getInt("tsize", 1).coerceIn(0, 3)]; setTextColor(c(col)); setPadding(0, dp(4), 0, dp(4))
        setTypeface(when (p.getInt("font", 0)) { 1 -> Typeface.SERIF; 2 -> Typeface.MONOSPACE; else -> Typeface.DEFAULT }, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    internal fun btn(s: String, bg: String, fg: String, f: () -> Unit) = Button(this).apply {
        text = s; setAllCaps(false); setTextColor(c(fg)); setPadding(dp(16), dp(12), dp(16), dp(12))
        val g = GradientDrawable(); g.setColor(c(bg)); g.cornerRadius = dp(24).toFloat(); background = g
        setOnClickListener { f() }
    }

    internal fun card(t: Th, vararg v: View): LinearLayout {
        val l = LinearLayout(this); l.orientation = LinearLayout.VERTICAL; val pv = listOf(6, 12, 18)[p.getInt("dens", 1).coerceIn(0, 2)]; l.setPadding(dp(16), dp(pv), dp(16), dp(pv))
        val g = GradientDrawable(); g.setColor(c(t.card)); g.cornerRadius = dp(20).toFloat(); l.background = g
        v.forEach { l.addView(it, lp(6)) }
        return l
    }

    internal fun sw(t: Th, s: String, k: String, d: Boolean, f: (Boolean) -> Unit = {}) = Switch(this).apply {
        text = s; setTextColor(c(t.tx)); isChecked = p.getBoolean(k, d); setPadding(0, dp(8), 0, dp(8))
        setOnCheckedChangeListener { _, v -> p.edit().putBoolean(k, v).apply(); f(v) }
    }

    internal fun chips(t: Th, items: List<String>, sel: Int, f: (Int) -> Unit): View {
        val v = LinearLayout(this); v.orientation = LinearLayout.VERTICAL
        items.chunked(3).forEachIndexed { r, row ->
            val h = LinearLayout(this)
            row.forEachIndexed { i, s ->
                val k = r * 3 + i
                val q = LinearLayout.LayoutParams(0, -2, 1f); q.setMargins(dp(3), dp(4), dp(3), 0)
                h.addView(btn(s, if (k == sel) t.ac else t.bg, if (k == sel) t.bg else t.tx) { f(k) }, q)
            }
            v.addView(h)
        }
        return v
    }

    internal fun hash(pin: String, salt: ByteArray): String =
        Base64.encodeToString(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(PBEKeySpec(pin.toCharArray(), salt, 40000, 256)).encoded, Base64.NO_WRAP)

    internal fun applySecure() {
        if (p.getBoolean("secure", true)) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s); Dbg.install(applicationContext); root = FrameLayout(this); setContentView(root); applySecure()
        root.filterTouchesWhenObscured = p.getBoolean("overlay", true); Dbg.log(this, "start " + Upd.name(this))
    }
    override fun onResume() {
        super.onResume()
        val d = listOf(0, 7, 14, 30)[p.getInt("inact", 0).coerceIn(0, 3)]
        if (d > 0 && p.contains("h") && System.currentTimeMillis() - p.getLong("lastUnlock", System.currentTimeMillis()) > d * 86400000L) { wipe(); return }
        if (unlocked && p.getBoolean("lockbg", true) && System.currentTimeMillis() - bgAt >= listOf(0L, 30000L, 60000L, 300000L)[p.getInt("lockdelay", 0).coerceIn(0, 3)]) { unlocked = false; scr = "home" }
        render()
    }
    override fun onStop() { super.onStop(); bgAt = System.currentTimeMillis() }
    internal fun back() { scr = if (scr.startsWith("cat:") || scr in setOf("user", "center", "debug", "upd")) "settings" else "home"; render() }
    override fun onBackPressed() { if (unlocked && scr != "home") back() else super.onBackPressed() }

    internal fun render() {
        val t = th()
        val w = wps[p.getInt("wp", 0).coerceIn(0, wps.size - 1)].second
        root.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, if (w.size < 2) intArrayOf(c(t.bg), c(t.bg)) else w)
        root.removeAllViews()
        if (!p.contains("h")) setup(t) else if (!unlocked) lock(t) else route(t)
    }

    internal fun center(t: Th, title: String, sub: String): LinearLayout {
        val l = LinearLayout(this); l.orientation = LinearLayout.VERTICAL; l.gravity = Gravity.CENTER; l.setPadding(dp(28), dp(28), dp(28), dp(28))
        l.addView(tv(title, 32f, t.tx, true)); l.addView(tv(sub, 14f, t.sub))
        root.addView(l, FrameLayout.LayoutParams(-1, -1)); return l
    }

    internal fun pinField(t: Th, hint: String): EditText {
        val e = EditText(this); e.hint = hint; e.setTextColor(c(t.tx)); e.setHintTextColor(c(t.sub))
        if (p.getBoolean("incog", true)) e.imeOptions = 0x1000000
        e.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD; return e
    }

    internal fun setup(t: Th) {
        val l = center(t, "Quantum Signal", "Create a PIN to lock the app.")
        val a = pinField(t, "New PIN (6 or more digits)"); val b = pinField(t, "Repeat PIN"); val m = tv("", 13f, "#FF6B57")
        l.addView(a, lp(16)); l.addView(b, lp(8)); l.addView(m)
        l.addView(btn("Create PIN", t.ac, t.bg) {
            val x = a.text.toString()
            if (x.length < 6) m.text = "Use at least 6 digits."
            else if (x != b.text.toString()) m.text = "The PINs don’t match."
            else {
                val salt = ByteArray(16); SecureRandom().nextBytes(salt)
                p.edit().putString("s", Base64.encodeToString(salt, Base64.NO_WRAP)).putString("h", hash(x, salt)).putInt("fails", 0).apply()
                unlocked = true; p.edit().putLong("lastUnlock", System.currentTimeMillis()).apply(); render()
            }
        }, lp(12))
    }

    internal fun lock(t: Th) {
        val l = center(t, "Quantum Signal", "Enter your PIN.")
        val a = pinField(t, "PIN"); val m = tv("", 13f, "#FF6B57")
        l.addView(a, lp(16)); l.addView(m)
        l.addView(btn("Unlock", t.ac, t.bg) {
            val now = System.currentTimeMillis(); val until = p.getLong("until", 0L)
            if (now < until) { m.text = "Too many tries. Wait ${(until - now) / 1000 + 1}s."; return@btn }
            val salt = Base64.decode(p.getString("s", ""), Base64.NO_WRAP); val e = hash(a.text.toString(), salt)
            if (p.contains("dh") && e == p.getString("dh", "")) { wipe(); return@btn }
            if (e == p.getString("h", "")) { p.edit().putInt("fails", 0).putLong("lastUnlock", now).apply(); unlocked = true; Dbg.log(this, "unlock ok"); render() }
            else {
                val n = p.getInt("fails", 0) + 1; p.edit().putInt("fails", n).apply(); Dbg.log(this, "wrong PIN ($n)")
                if (p.getBoolean("wipeFails", false) && n >= listOf(5, 10, 15)[p.getInt("failn", 1).coerceIn(0, 2)]) wipe()
                else { if (p.getBoolean("lockout", true) && n >= 3) p.edit().putLong("until", now + minOf(300L, 5L shl minOf(n - 3, 6)) * 1000L).apply(); m.text = "Wrong PIN ($n)" }
            }
        }, lp(12))
    }

    internal fun home(t: Th) {
        val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL
        root.addView(col, FrameLayout.LayoutParams(-1, -1))
        val un = Vault.dec(p.getString("user", "") ?: "") ?: ""
        val top = LinearLayout(this); top.gravity = Gravity.CENTER_VERTICAL; top.setPadding(dp(16), dp(38), dp(12), dp(6))
        val av = avatar(t, if (un.isEmpty()) "Q" else un, 40); av.setOnClickListener { scr = "settings"; render() }
        top.addView(av)
        val ti = tv("Quantum Signal", 22f, t.tx, true); ti.setPadding(dp(12), 0, 0, 0); top.addView(ti, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(btn("✎", t.card, t.tx) { scr = "chat"; render() }); top.addView(btn("⚙", t.card, t.tx) { scr = "settings"; render() })
        col.addView(top)
        val box = LinearLayout(this); box.orientation = LinearLayout.VERTICAL; box.setPadding(dp(16), dp(4), dp(16), dp(16))
        val sv = ScrollView(this); sv.addView(box); col.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))
        if (p.getBoolean("warnInt", true)) {
            val bad = Chk.run(this, p).count { !it.second }
            if (bad > 0) box.addView(nav(t, "$bad security items to review", "Tap to open the Security Center") { scr = "center"; render() }, lp(0))
        }
        val last = Msgs.load(this).lastOrNull()
        box.addView(chatRow(t, "Note to Self", if (last == null) "Tap to write a note" else if (p.getBoolean("hidePrev", false)) "Message" else last.text,
            if (last == null) "" else fmt(last.id), if (last == null || !p.getBoolean("ticks", true)) "" else tick(last.st).substringBefore(" ")) { scr = "chat"; render() }, lp(8))
        box.addView(card(t, tv("Coming next", 14f, t.ac, true), tv("Accounts and email verification\nPost-quantum encrypted messaging\nVoice and video calls", 13f, t.tx)), lp(14))
    }

    internal fun wipe() {
        p.edit().clear().commit(); filesDir.deleteRecursively(); cacheDir.deleteRecursively()
        try { (getSystemService(ACTIVITY_SERVICE) as ActivityManager).clearApplicationUserData() } catch (e: Exception) { finishAndRemoveTask() }
    }
}

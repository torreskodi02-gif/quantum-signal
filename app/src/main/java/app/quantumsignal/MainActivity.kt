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
    private val ths = listOf(
        Th("Cobalt", "#0F1240", "#1A1F5E", "#7C84FF", "#F2F3FF", "#A9AEE8"),
        Th("Midnight", "#0A0D12", "#141A22", "#7DD3FC", "#FFFFFF", "#9AA4B2"),
        Th("Ember", "#1C0F0C", "#2B1914", "#FF7A59", "#FFF2EC", "#D9A596"),
        Th("Forest", "#0C1A14", "#14281E", "#4ADE80", "#EAFBF1", "#8FBFA2"),
        Th("Paper", "#F3F1FA", "#FFFFFF", "#3B30F0", "#14123A", "#55527A"),
        Th("Mono", "#000000", "#111111", "#FFFFFF", "#FFFFFF", "#8A8A8A"))
    private val wps = listOf(
        "Plain" to intArrayOf(),
        "Aurora" to intArrayOf(0xFF1B2B6B.toInt(), 0xFF0F6E6E.toInt()),
        "Sunset" to intArrayOf(0xFF3B1C5A.toInt(), 0xFFB5435A.toInt()),
        "Deep sea" to intArrayOf(0xFF021B3A.toInt(), 0xFF0B5C7A.toInt()),
        "Graphite" to intArrayOf(0xFF111111.toInt(), 0xFF3A3A3A.toInt()),
        "Dawn" to intArrayOf(0xFFF7D9C4.toInt(), 0xFFC9B6F2.toInt()))
    private val p by lazy { getSharedPreferences("qs", MODE_PRIVATE) }
    private lateinit var root: FrameLayout
    private var unlocked = false
    private var tab = 0
    private var busy = false

    private fun c(s: String) = Color.parseColor(s)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun lp(m: Int = 8): LinearLayout.LayoutParams { val q = LinearLayout.LayoutParams(-1, -2); q.setMargins(0, dp(m), 0, 0); return q }
    private fun th() = ths[p.getInt("theme", 0).coerceIn(0, ths.size - 1)]

    private fun tv(s: String, sz: Float, col: String, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = sz; setTextColor(c(col)); setPadding(0, dp(4), 0, dp(4))
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun btn(s: String, bg: String, fg: String, f: () -> Unit) = Button(this).apply {
        text = s; setAllCaps(false); setTextColor(c(fg)); setPadding(dp(16), dp(12), dp(16), dp(12))
        val g = GradientDrawable(); g.setColor(c(bg)); g.cornerRadius = dp(24).toFloat(); background = g
        setOnClickListener { f() }
    }

    private fun card(t: Th, vararg v: View): LinearLayout {
        val l = LinearLayout(this); l.orientation = LinearLayout.VERTICAL; l.setPadding(dp(16), dp(12), dp(16), dp(12))
        val g = GradientDrawable(); g.setColor(c(t.card)); g.cornerRadius = dp(20).toFloat(); l.background = g
        v.forEach { l.addView(it, lp(6)) }
        return l
    }

    private fun sw(t: Th, s: String, k: String, d: Boolean, f: (Boolean) -> Unit = {}) = Switch(this).apply {
        text = s; setTextColor(c(t.tx)); isChecked = p.getBoolean(k, d); setPadding(0, dp(8), 0, dp(8))
        setOnCheckedChangeListener { _, v -> p.edit().putBoolean(k, v).apply(); f(v) }
    }

    private fun chips(t: Th, items: List<String>, sel: Int, f: (Int) -> Unit): View {
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

    private fun hash(pin: String, salt: ByteArray): String =
        Base64.encodeToString(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(PBEKeySpec(pin.toCharArray(), salt, 40000, 256)).encoded, Base64.NO_WRAP)

    private fun applySecure() {
        if (p.getBoolean("secure", true)) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    override fun onCreate(s: Bundle?) { super.onCreate(s); root = FrameLayout(this); setContentView(root); applySecure() }
    override fun onResume() { super.onResume(); render() }
    override fun onStop() { super.onStop(); if (p.getBoolean("lockbg", true)) unlocked = false }

    private fun render() {
        val t = th()
        val w = wps[p.getInt("wp", 0).coerceIn(0, wps.size - 1)].second
        root.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, if (w.size < 2) intArrayOf(c(t.bg), c(t.bg)) else w)
        root.removeAllViews()
        if (!p.contains("h")) setup(t) else if (!unlocked) lock(t) else home(t)
    }

    private fun center(t: Th, title: String, sub: String): LinearLayout {
        val l = LinearLayout(this); l.orientation = LinearLayout.VERTICAL; l.gravity = Gravity.CENTER; l.setPadding(dp(28), dp(28), dp(28), dp(28))
        l.addView(tv(title, 32f, t.tx, true)); l.addView(tv(sub, 14f, t.sub))
        root.addView(l, FrameLayout.LayoutParams(-1, -1)); return l
    }

    private fun pinField(t: Th, hint: String): EditText {
        val e = EditText(this); e.hint = hint; e.setTextColor(c(t.tx)); e.setHintTextColor(c(t.sub))
        e.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD; return e
    }

    private fun setup(t: Th) {
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
                unlocked = true; render()
            }
        }, lp(12))
    }

    private fun lock(t: Th) {
        val l = center(t, "Quantum Signal", "Enter your PIN.")
        val a = pinField(t, "PIN"); val m = tv("", 13f, "#FF6B57")
        l.addView(a, lp(16)); l.addView(m)
        l.addView(btn("Unlock", t.ac, t.bg) {
            val salt = Base64.decode(p.getString("s", ""), Base64.NO_WRAP)
            if (hash(a.text.toString(), salt) == p.getString("h", "")) { p.edit().putInt("fails", 0).apply(); unlocked = true; render() }
            else {
                val n = p.getInt("fails", 0) + 1; p.edit().putInt("fails", n).apply()
                if (p.getBoolean("wipeFails", false) && n >= 10) wipe() else m.text = "Wrong PIN ($n)"
            }
        }, lp(12))
    }

    private fun home(t: Th) {
        val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL
        root.addView(col, FrameLayout.LayoutParams(-1, -1))
        val top = LinearLayout(this); top.orientation = LinearLayout.VERTICAL; top.setPadding(dp(18), dp(40), dp(18), dp(6))
        top.addView(tv("Quantum Signal", 24f, t.tx, true)); top.addView(tv("Early build · messaging is not connected yet", 12f, t.sub))
        col.addView(top)
        val tabs = LinearLayout(this); tabs.setPadding(dp(12), dp(4), dp(12), dp(4))
        listOf("Chats", "Look", "Security").forEachIndexed { i, s ->
            val q = LinearLayout.LayoutParams(0, -2, 1f); q.setMargins(dp(4), 0, dp(4), 0)
            tabs.addView(btn(s, if (tab == i) t.ac else t.card, if (tab == i) t.bg else t.tx) { tab = i; render() }, q)
        }
        col.addView(tabs)
        val box = LinearLayout(this); box.orientation = LinearLayout.VERTICAL; box.setPadding(dp(16), dp(8), dp(16), dp(16))
        val sv = ScrollView(this); sv.addView(box); col.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))
        when (tab) { 0 -> chats(t, box); 1 -> look(t, box); else -> sec(t, box) }
        val bar = LinearLayout(this); bar.setPadding(dp(16), dp(6), dp(16), dp(16))
        bar.addView(btn("⚠  Distress: wipe everything", "#D92D20", "#FFFFFF") { panic() }, LinearLayout.LayoutParams(-1, -2))
        col.addView(bar)
    }

    private fun chats(t: Th, box: LinearLayout) {
        box.addView(card(t, tv("No conversations yet", 17f, t.tx, true), tv("Accounts, encrypted chat and calls arrive in the next build steps.", 13f, t.sub)), lp(0))
        box.addView(card(t, tv("Build progress", 14f, t.ac, true),
            tv("✓ PIN lock and distress wipe\n✓ Themes and wallpapers\n✓ In-app updates\n• Accounts and email verification\n• Post-quantum encrypted messaging\n• Voice and video calls", 13f, t.tx)), lp(12))
    }

    private fun look(t: Th, box: LinearLayout) {
        box.addView(card(t, tv("Theme", 14f, t.ac, true), chips(t, ths.map { it.n }, p.getInt("theme", 0)) { p.edit().putInt("theme", it).apply(); render() }), lp(0))
        box.addView(card(t, tv("Wallpaper", 14f, t.ac, true), chips(t, wps.map { it.first }, p.getInt("wp", 0)) { p.edit().putInt("wp", it).apply(); render() }), lp(12))
    }

    private fun sec(t: Th, box: LinearLayout) {
        box.addView(card(t, tv("Privacy", 14f, t.ac, true),
            sw(t, "Block screenshots and screen recording", "secure", true) { applySecure() },
            sw(t, "Lock when the app goes to the background", "lockbg", true),
            sw(t, "Wipe everything after 10 wrong PINs", "wipeFails", false),
            btn("Change PIN", t.bg, t.tx) { p.edit().remove("h").apply(); render() }), lp(0))
        val cur = Upd.cur(this); val nv = p.getInt("newv", 0); val url = p.getString("newurl", null)
        val vt = tv("Version ${Upd.name(this)}" + (if (nv > cur) " · new build $nv available" else ""), 13f, t.sub)
        val v = ArrayList<View>(); v.add(tv("Updates", 14f, t.ac, true)); v.add(vt)
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
        v.add(btn("Check for updates", t.bg, t.tx) {
            Upd.check(this) { code, u, err ->
                if (err == null) p.edit().putInt("newv", code).putString("newurl", u).apply()
                toast(err ?: (if (code > Upd.cur(this)) "Update available" else "You’re on the latest version")); render()
            }
        })
        box.addView(card(t, *v.toTypedArray()), lp(12))
    }

    private fun panic() {
        val d = Dialog(this, android.R.style.Theme_Material_NoActionBar_Fullscreen)
        val l = LinearLayout(this); l.orientation = LinearLayout.VERTICAL; l.gravity = Gravity.CENTER; l.setPadding(dp(28), dp(28), dp(28), dp(28)); l.setBackgroundColor(c("#7A0B0B"))
        l.addView(tv("Are you sure?", 30f, "#FFFFFF", true))
        l.addView(tv("This permanently erases all chats, keys, settings and your PIN from this phone, then closes the app. It can’t be undone.", 15f, "#FFD9D9"), lp(12))
        l.addView(btn("Cancel", "#FFFFFF", "#7A0B0B") { d.dismiss() }, lp(28))
        l.addView(btn("Yes, wipe everything", "#000000", "#FFFFFF") { wipe() }, lp(10))
        d.setContentView(l); d.show()
    }

    private fun wipe() {
        p.edit().clear().commit(); filesDir.deleteRecursively(); cacheDir.deleteRecursively()
        try { (getSystemService(ACTIVITY_SERVICE) as ActivityManager).clearApplicationUserData() } catch (e: Exception) { finishAndRemoveTask() }
    }
}

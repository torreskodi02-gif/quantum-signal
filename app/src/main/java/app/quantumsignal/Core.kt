package app.quantumsignal

import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object Vault {
    private const val A = "qs_vault"
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null)
        val k = ks.getKey(A, null) as? SecretKey
        if (k != null) return k
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(A, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return g.generateKey()
    }
    fun enc(s: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key())
        val iv = c.iv; return Base64.encodeToString(iv + c.doFinal(s.toByteArray()), Base64.NO_WRAP)
    }
    fun dec(s: String): String? = try {
        val b = Base64.decode(s, Base64.NO_WRAP); val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b, 0, 12)); String(c.doFinal(b, 12, b.size - 12))
    } catch (e: Exception) { null }
}

class Msg(val id: Long, val text: String, val st: Int)

object Msgs {
    private fun f(c: Context) = File(c.filesDir, "notes.dat")
    fun load(c: Context): List<Msg> {
        val x = f(c); if (!x.exists()) return emptyList()
        return x.readLines().mapNotNull { l ->
            try { val a = (Vault.dec(l) ?: "").split("|", limit = 3); Msg(a[0].toLong(), String(Base64.decode(a[2], Base64.NO_WRAP)), a[1].toInt()) } catch (e: Exception) { null }
        }
    }
    fun save(c: Context, m: List<Msg>) {
        f(c).writeText(m.joinToString("\n") { Vault.enc("${it.id}|${it.st}|" + Base64.encodeToString(it.text.toByteArray(), Base64.NO_WRAP)) })
    }
}

object Dbg {
    private fun f(c: Context) = File(c.filesDir, "debug.log")
    fun log(c: Context, s: String, force: Boolean = false) {
        if (!force && !c.getSharedPreferences("qs", 0).getBoolean("dbg", true)) return
        try {
            val x = f(c); val t = (if (x.exists()) x.readText() else "") + java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date()) + " " + s + "\n"
            x.writeText(t.takeLast(20000))
        } catch (e: Exception) {}
    }
    fun read(c: Context): String = try { f(c).readText() } catch (e: Exception) { "" }
    fun clear(c: Context) { try { f(c).delete() } catch (e: Exception) {} }
    fun install(c: Context) {
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e -> log(c, "CRASH in ${t.name}: " + android.util.Log.getStackTraceString(e), true); old?.uncaughtException(t, e) }
    }
}

object Chk {
    @Suppress("DEPRECATION")
    fun run(c: Context, p: SharedPreferences): List<Triple<String, Boolean, String>> {
        val r = ArrayList<Triple<String, Boolean, String>>()
        fun add(n: String, ok: Boolean, d: String = "") { r.add(Triple(n, ok, d)) }
        val cr = c.contentResolver; val fl = c.applicationInfo.flags
        add("PIN lock enabled", p.contains("h"))
        add("Duress PIN set", p.contains("dh"), "Optional: a PIN that silently wipes the app")
        add("Screenshot protection", p.getBoolean("secure", true))
        add("Locks when you leave the app", p.getBoolean("lockbg", true))
        add("Blocks taps from overlay apps", p.getBoolean("overlay", true))
        add("Encrypted storage works", try { Vault.dec(Vault.enc("ok")) == "ok" } catch (e: Exception) { false })
        add("Cloud backup disabled", (fl and ApplicationInfo.FLAG_ALLOW_BACKUP) == 0)
        add("App is not debuggable", (fl and ApplicationInfo.FLAG_DEBUGGABLE) == 0)
        add("No debugger attached", !Debug.isDebuggerConnected())
        add("No root indicators", listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/system/app/Superuser.apk").none { File(it).exists() } && !(Build.TAGS ?: "").contains("test-keys"))
        add("Not running on an emulator", !(Build.FINGERPRINT.startsWith("generic") || Build.HARDWARE.contains("goldfish") || Build.HARDWARE.contains("ranchu")))
        add("USB debugging is off", Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, 0) == 0, "Turn it off in Developer options when you don't need it")
        add("Developer options are off", Settings.Global.getInt(cr, "development_settings_enabled", 0) == 0)
        add("Phone has a screen lock", (c.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceSecure)
        val es = (c.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager).storageEncryptionStatus
        add("Phone storage is encrypted", es == DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE || es == 5)
        val sig = try { MessageDigest.getInstance("SHA-256").digest(c.packageManager.getPackageInfo(c.packageName, PackageManager.GET_SIGNATURES).signatures[0].toByteArray()).joinToString("") { "%02x".format(it) } } catch (e: Exception) { "" }
        if (sig.isNotEmpty() && !p.contains("sig")) p.edit().putString("sig", sig).apply()
        add("App signature unchanged", sig.isNotEmpty() && sig == p.getString("sig", sig))
        val sp = Build.VERSION.SECURITY_PATCH ?: ""
        val fresh = try { System.currentTimeMillis() - java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(sp)!!.time < 365L * 86400000L } catch (e: Exception) { false }
        add("Security patch under a year old", fresh, "Your patch level: $sp")
        return r
    }
}

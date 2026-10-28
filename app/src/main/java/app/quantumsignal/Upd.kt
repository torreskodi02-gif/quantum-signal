package app.quantumsignal

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object Upd {
    private const val API = "https://api.github.com/repos/torreskodi02-gif/quantum-signal/releases/latest"
    private val ui = Handler(Looper.getMainLooper())

    @Suppress("DEPRECATION")
    fun cur(c: Context) = c.packageManager.getPackageInfo(c.packageName, 0).versionCode
    fun name(c: Context) = c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "?"

    private fun open(u: String): HttpURLConnection {
        val h = URL(u).openConnection() as HttpURLConnection
        h.connectTimeout = 15000; h.readTimeout = 30000; h.instanceFollowRedirects = true
        h.setRequestProperty("User-Agent", "VoltForge"); h.setRequestProperty("Accept", "application/vnd.github+json")
        return h
    }

    fun check(c: Context, cb: (Int, String?, String?) -> Unit) {
        Thread {
            var code = 0; var url: String? = null; var err: String? = null
            try {
                val j = JSONObject(open(API).inputStream.bufferedReader().readText())
                code = j.getString("tag_name").trim('v', 'V').toInt()
                val a = j.getJSONArray("assets")
                for (i in 0 until a.length()) if (a.getJSONObject(i).getString("name") == "quantumsignal.apk") url = a.getJSONObject(i).getString("browser_download_url")
            } catch (e: Exception) { err = "Couldn't reach the update server. Check your connection." }
            ui.post { cb(code, url, err) }
        }.start()
    }

    fun install(c: Context, url: String, prog: (Int) -> Unit, done: (String?) -> Unit) {
        Thread {
            var err: String? = null
            try {
                val pi = c.packageManager.packageInstaller
                val sp = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                sp.setAppPackageName(c.packageName)
                val id = pi.createSession(sp); val s = pi.openSession(id)
                val h = open(url); val total = h.contentLength.toLong()
                h.inputStream.use { inp ->
                    s.openWrite("vf", 0, if (total > 0) total else -1L).use { out ->
                        val buf = ByteArray(65536); var n = 0L; var r = 0; var last = -1
                        while (inp.read(buf).also { r = it } > 0) {
                            out.write(buf, 0, r); n += r
                            if (total > 0) { val pc = (n * 100 / total).toInt(); if (pc != last) { last = pc; ui.post { prog(pc) } } }
                        }
                        s.fsync(out)
                    }
                }
                val pend = PendingIntent.getBroadcast(c, id, Intent(c, InstRcv::class.java), PendingIntent.FLAG_UPDATE_CURRENT)
                s.commit(pend.intentSender); s.close()
            } catch (e: Exception) { err = "Update failed: ${e.message}" }
            ui.post { done(err) }
        }.start()
    }
}

class InstRcv : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val n = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (n != null) { n.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); c.startActivity(n) }
            }
            PackageInstaller.STATUS_SUCCESS -> {}
            else -> Toast.makeText(c, "Update not installed: " + (i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""), Toast.LENGTH_LONG).show()
        }
    }
}

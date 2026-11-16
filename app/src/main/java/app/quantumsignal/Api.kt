package app.quantumsignal

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Server client for Quantum Signal (native).
 *
 * Talks to the same Cloudflare worker as the web app, so native and web
 * clients interoperate. Plain HttpURLConnection only — no extra deps.
 *
 * All methods run on the CALLING thread (callers must use a background
 * thread). All methods return the decoded JSON response and throw [ApiErr]
 * on any failure. Tokens and private keys are never logged.
 *
 * Server: https://quantum-signal.qs-066647a2.workers.dev
 * Auth: header "x-token: <token>" on every endpoint except /register
 * and GET /user/{name}. Errors are JSON {e:"..."} with an HTTP status.
 */
object Api {

    /** All known server URLs, in failover order. */
    var servers: MutableList<String> =
        mutableListOf("https://quantum-signal.qs-066647a2.workers.dev")

    /** The server currently in use (defaults to servers[0]). */
    var server: String = servers[0]

    /** Thrown for HTTP errors (code = HTTP status) and network failures. */
    class ApiErr(message: String, val code: Int = 0) : Exception(message)

    /** Servers to try, in order: the current one first, then the rest. */
    private fun attemptOrder(): List<String> {
        val ordered = ArrayList<String>()
        val cur = server.trimEnd('/')
        if (cur.isNotBlank()) ordered.add(cur)
        for (s in servers) {
            val b = s.trimEnd('/')
            if (b.isNotBlank() && b !in ordered) ordered.add(b)
        }
        if (ordered.isEmpty()) ordered.add("https://quantum-signal.qs-066647a2.workers.dev")
        return ordered
    }

    /**
     * One HTTP request with failover. A server that ANSWERS with an HTTP
     * error (4xx/5xx) is a real answer — its {e} message is thrown as
     * ApiErr and no other server is tried. Only a network failure
     * (IOException / timeout, i.e. the server did not answer at all)
     * moves on to the next server in [servers]. If none answer,
     * ApiErr("No connection to the server") is thrown. On success the
     * answering server becomes the current [server].
     */
    private fun request(
        method: String,
        path: String,
        body: JSONObject?,
        token: String?,
        timeoutMs: Int,
        readTimeoutMs: Int
    ): JSONObject {
        for (base in attemptOrder()) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(base + path).openConnection() as HttpURLConnection).apply {
                    requestMethod = method
                    connectTimeout = timeoutMs
                    readTimeout = readTimeoutMs
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "application/json")
                    if (token != null) setRequestProperty("x-token", token)
                    if (body != null && (method == "POST" || method == "DELETE")) {
                        // DELETE with a body: the worker reads it (see
                        // worker-v090.js: raw body is read for POST/DELETE).
                        doOutput = true
                        outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                    }
                }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                if (code in 200..299) {
                    server = base
                    if (text.isBlank()) return JSONObject()
                    return try {
                        JSONObject(text)
                    } catch (e: Exception) {
                        throw ApiErr("Bad response from server", code)
                    }
                }
                val msg = try {
                    JSONObject(text).optString("e", "").ifBlank { "Server error ($code)" }
                } catch (e: Exception) {
                    "Server error ($code)"
                }
                throw ApiErr(msg, code)
            } catch (e: ApiErr) {
                throw e // HTTP error: the server answered, do not fail over.
            } catch (e: IOException) {
                // No answer from this server: try the next one.
            } finally {
                try { conn?.disconnect() } catch (e: Exception) { /* ignore */ }
            }
        }
        throw ApiErr("No connection to the server")
    }

    /** POST a JSON body, return the decoded JSON response. */
    private fun post(
        path: String,
        body: JSONObject,
        token: String? = null,
        timeoutMs: Int = 30000,
        readTimeoutMs: Int = timeoutMs
    ): JSONObject = request("POST", path, body, token, timeoutMs, readTimeoutMs)

    /** GET, return the decoded JSON response. */
    private fun get(path: String, token: String? = null): JSONObject =
        request("GET", path, null, token, 30000, 30000)

    // ---- account ----

    /** POST /register {u, pub(JWK), auth(hex, optional), vault({iv,ct}, optional)} -> {token}. 409 if taken. */
    fun register(u: String, pub: JSONObject, auth: String?, vault: JSONObject?): JSONObject {
        val b = JSONObject().put("u", u).put("pub", pub)
        if (auth != null) b.put("auth", auth)
        if (vault != null) b.put("vault", vault)
        return post("/register", b)
    }

    /** POST /login {u, auth} -> {token, pub, vault}. 401 on bad login. */
    fun login(u: String, auth: String): JSONObject =
        post("/login", JSONObject().put("u", u).put("auth", auth))

    /** GET /user/{name} -> {pub}. 404 if not found. */
    fun findUser(name: String): JSONObject =
        get("/user/" + URLEncoder.encode(name, "UTF-8"))

    /** POST /password {u, auth, old, vault} -> {ok}. */
    fun changePassword(u: String, auth: String, old: String, vault: JSONObject, token: String): JSONObject =
        post("/password", JSONObject().put("u", u).put("auth", auth).put("old", old).put("vault", vault), token)

    /** POST /logout-all {u} -> {ok, removed}. Invalidates every other token. */
    fun logoutAll(u: String, token: String): JSONObject =
        post("/logout-all", JSONObject().put("u", u), token)

    /** POST /logout {u} -> {ok}. Invalidates this token. */
    fun logout(u: String, token: String): JSONObject =
        post("/logout", JSONObject().put("u", u), token)

    // ---- messaging ----

    /** POST /send {from, to, ct, iv} -> {ok, id}. ct/iv are base64. */
    fun send(from: String, to: String, ct: String, iv: String, token: String): JSONObject =
        post("/send", JSONObject().put("from", from).put("to", to).put("ct", ct).put("iv", iv), token)

    /**
     * POST /inbox {u, wait(0-50), ack:[ids]} -> {m:[{id, from, ct, iv, ts}]}.
     * Long-polls up to `wait` seconds; messages stay until acked. The read
     * timeout is (wait+10)s so a long-poll is never cut off early.
     */
    fun inbox(u: String, wait: Int, ack: List<String>, token: String): JSONObject {
        val w = wait.coerceIn(0, 50)
        val b = JSONObject().put("u", u).put("wait", w).put("ack", JSONArray(ack))
        return post("/inbox", b, token, 30000, (w + 10) * 1000)
    }

    // ---- push ----

    /** POST /push-token {u, token:<FCM token>} -> {ok}. `token` is the auth token (x-token header). */
    fun pushToken(u: String, token: String, fcmToken: String): JSONObject =
        post("/push-token", JSONObject().put("u", u).put("token", fcmToken), token)

    /** DELETE /push-token {u, token?:<FCM token>} -> {ok}. fcmToken null deletes all tokens for the user. */
    fun deletePushToken(u: String, token: String, fcmToken: String?): JSONObject {
        val b = JSONObject().put("u", u)
        if (fcmToken != null) b.put("token", fcmToken)
        return request("DELETE", "/push-token", b, token, 30000, 30000)
    }

    // ---- cloud backup ----

    /** POST /backup {u, iv, data} -> {ok}. Rate-limited to 1/min/user. */
    fun backupUp(u: String, iv: String, data: String, token: String): JSONObject =
        post("/backup", JSONObject().put("u", u).put("iv", iv).put("data", data), token)

    /** GET /backup?u= -> {data, iv, ts}. 404 if no backup exists. */
    fun backupDown(u: String, token: String): JSONObject =
        get("/backup?u=" + URLEncoder.encode(u, "UTF-8"), token)

    /** DELETE /backup {u} -> {ok}. */
    fun backupDel(u: String, token: String): JSONObject =
        request("DELETE", "/backup", JSONObject().put("u", u), token, 30000, 30000)
}

package app.quantumsignal

import android.util.Base64
import org.json.JSONObject
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import java.text.Normalizer
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * End-to-end crypto for Quantum Signal (native).
 *
 * This MUST stay byte-compatible with the web app (www/index.html), so a
 * native client and a web client can message each other:
 *  - Identity: ECDH P-256 key pair. Public key is a JWK {kty,crv,x,y};
 *    private key is a JWK {kty,crv,x,y,d}. x/y/d are base64url of the
 *    32-byte big-endian unsigned integers.
 *  - Per-peer key: ECDH with the peer's public key + my private key.
 *    The shared secret is the raw x-coordinate (32 bytes) and is used
 *    DIRECTLY as the AES-256 key — no extra KDF. (Verified: WebCrypto's
 *    ECDH deriveKey returns exactly the raw x-coordinate, and Java's
 *    KeyAgreement.generateSecret() returns the same bytes.)
 *  - Message: AES-GCM-256, random 12-byte IV, 128-bit tag. ct is the
 *    ciphertext with the 16-byte tag appended. iv/ct are standard base64.
 *    Plaintext is JSON: {"k":"m","id","t","q"} (message),
 *    {"k":"d","ids"} (delivered), {"k":"r","ids"} (read).
 *  - Password KDF: PBKDF2-HMAC-SHA256, salt "qs-v1:"+username, 600,000
 *    iterations, 512 bits. First 32 bytes = auth (hex, sent to the server),
 *    last 32 bytes = the vault AES-GCM key. Password is NFKC-normalized.
 *  - Vault: the private-key JWK (JSON) encrypted with the vault key
 *    (AES-GCM, random 12-byte IV). Stored on the server as {iv,ct}.
 *  - Backup KDF: same, but salt "qs-backup:"+username.
 */
object Crypto {
    private val rng = SecureRandom()

    // ---- base64 (standard, no wrapping/newlines) ----
    fun b64e(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
    fun b64d(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)
    // ---- base64url (JWK: no padding, - and _) ----
    fun b64uE(b: ByteArray): String = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    fun b64uD(s: String): ByteArray = Base64.decode(s, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun u32(n: BigInteger): ByteArray {
        val b = n.toByteArray()
        return when {
            b.size == 32 -> b
            b.size == 33 && b[0] == 0.toByte() -> b.copyOfRange(1, 33)
            b.size < 32 -> ByteArray(32 - b.size) + b
            else -> b.copyOfRange(b.size - 32, b.size)
        }
    }

    /** P-256 curve parameters. */
    fun params(): ECParameterSpec {
        val ap = java.security.AlgorithmParameters.getInstance("EC")
        ap.init(ECGenParameterSpec("secp256r1"))
        return ap.getParameterSpec(ECParameterSpec::class.java)
    }

    // ---- identity (ECDH P-256 key pair) ----
    data class Identity(val pub: JSONObject, val priv: JSONObject)

    fun generateIdentity(): Identity {
        val kg = KeyPairGenerator.getInstance("EC")
        kg.initialize(ECGenParameterSpec("secp256r1"), rng)
        val kp = kg.generateKeyPair()
        val pub = kp.public as java.security.interfaces.ECPublicKey
        val priv = kp.private as java.security.interfaces.ECPrivateKey
        val x = b64uE(u32(pub.w.affineX)); val y = b64uE(u32(pub.w.affineY))
        val d = b64uE(u32(priv.s))
        val pubJ = JSONObject().apply {
            put("kty", "EC"); put("crv", "P-256"); put("x", x); put("y", y)
        }
        val privJ = JSONObject().apply {
            put("kty", "EC"); put("crv", "P-256"); put("x", x); put("y", y); put("d", d)
        }
        return Identity(pubJ, privJ)
    }

    fun pubKeyFromJwk(j: JSONObject): java.security.PublicKey {
        val x = BigInteger(1, b64uD(j.getString("x")))
        val y = BigInteger(1, b64uD(j.getString("y")))
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), params()))
    }

    fun privKeyFromJwk(j: JSONObject): java.security.PrivateKey {
        val d = BigInteger(1, b64uD(j.getString("d")))
        return KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(d, params()))
    }

    // ---- per-peer shared AES key (raw ECDH x-coordinate, used directly) ----
    fun sharedKey(peerPub: JSONObject, myPriv: JSONObject): ByteArray {
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(privKeyFromJwk(myPriv))
        ka.doPhase(pubKeyFromJwk(peerPub), true)
        val s = ka.generateSecret()
        // generateSecret() returns the raw x-coordinate (32 bytes for P-256).
        return if (s.size == 32) s else u32(BigInteger(1, s))
    }

    // ---- AES-GCM message encrypt/decrypt ----
    data class Ct(val iv: String, val ct: String)

    fun encrypt(key: ByteArray, plain: ByteArray): Ct {
        val iv = ByteArray(12).also { rng.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return Ct(b64e(iv), b64e(c.doFinal(plain)))
    }

    fun decrypt(key: ByteArray, ivB64: String, ctB64: String): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, b64d(ivB64)))
        return c.doFinal(b64d(ctB64))
    }

    fun encryptMsg(peerPub: JSONObject, myPriv: JSONObject, plain: String): Ct =
        encrypt(sharedKey(peerPub, myPriv), plain.toByteArray(Charsets.UTF_8))

    fun decryptMsg(peerPub: JSONObject, myPriv: JSONObject, iv: String, ct: String): String =
        String(decrypt(sharedKey(peerPub, myPriv), iv, ct), Charsets.UTF_8)

    // ---- password KDF: PBKDF2-HMAC-SHA256, 600k iters, 512 bits ----
    data class Kdf(val authHex: String, val key: ByteArray)

    fun kdf(username: String, password: String, saltPrefix: String = "qs-v1:"): Kdf {
        val pw = Normalizer.normalize(password, Normalizer.Form.NFKC)
        val salt = (saltPrefix + username).toByteArray(Charsets.UTF_8)
        val spec = PBEKeySpec(pw.toCharArray(), salt, 600000, 512)
        val bits = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        val auth = bits.copyOfRange(0, 32).joinToString("") { "%02x".format(it) }
        return Kdf(auth, bits.copyOfRange(32, 64))
    }

    /** Backup KDF: same, salt "qs-backup:"+username. */
    fun bkdf(username: String, password: String): ByteArray =
        kdf(username, password, "qs-backup:").key

    // ---- vault: private-key JWK JSON encrypted with the KDF key ----
    fun encryptVault(vaultKey: ByteArray, privJwkJson: String): Ct =
        encrypt(vaultKey, privJwkJson.toByteArray(Charsets.UTF_8))

    fun decryptVault(vaultKey: ByteArray, iv: String, ct: String): String =
        String(decrypt(vaultKey, iv, ct), Charsets.UTF_8)
}

package com.fastkiwi.rememberme.data

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Optional password-encrypted backup (F-086): the bytes of a backup zip ([Backup.export]) sealed with a password.
 * Platform crypto only (javax.crypto, the same on Android 26+ and the JVM). The Windows app cannot open it yet.
 *
 * File format, version 1 (all integers big-endian), so another implementation (Windows, Node's crypto) can
 * read and write it:
 *
 * ```
 * offset size  field
 *  0      5    magic       ASCII "RMBK1"
 *  5      1    version     1
 *  6      4    iterations  PBKDF2 iteration count, unsigned (written: 600,000; read: 1 .. 10,000,000)
 * 10     16    salt        random
 * 26     12    nonce       random AES-GCM IV
 * 38      8    length      plaintext length in bytes, unsigned (the ciphertext has the same length)
 * 46     16    check       key check: HMAC-SHA256(master, "RMBK1 check"), first 16 bytes
 * 62      n    ciphertext  AES-256-GCM(key, nonce, plaintext, aad = bytes 0..61)
 * 62+n   16    tag         GCM tag (128 bits)
 * ```
 *
 * master = PBKDF2-HMAC-SHA256(password as UTF-8 bytes, no normalization; salt; iterations; 32 bytes)
 * (RFC 8018; computed here with HmacSHA256 so the password encoding is UTF-8 on every platform);
 * key = HMAC-SHA256(master, "RMBK1 key"). The header (bytes 0..61) is the AAD, so no header byte can be changed
 * unnoticed. Reading: a wrong magic, version, iteration count or length is [Damaged]; a check that does not
 * match is [WrongPassword]; a check that matches but a GCM tag that fails is [Damaged]. Nothing is returned
 * before the tag is verified.
 *
 * The caller owns [password] and should clear it after use; every copy made here (UTF-8 bytes, master, key,
 * HMAC blocks) is zeroed before returning.
 */
object BackupCrypto {
    const val MAGIC = "RMBK1"
    const val VERSION = 1
    const val ITERATIONS = 600_000
    internal const val MAX_ITERATIONS = 10_000_000
    private const val SALT = 16
    private const val NONCE = 12
    private const val CHECK = 16
    private const val TAG = 16
    internal const val HEADER = 5 + 1 + 4 + SALT + NONCE + 8 + CHECK

    class WrongPassword : Exception()
    class Damaged : Exception()

    private val random = SecureRandom()
    private val magic = MAGIC.toByteArray(Charsets.US_ASCII)

    fun isEncrypted(data: ByteArray): Boolean = data.size >= magic.size && (magic.indices).all { data[it] == magic[it] }

    /** Seals [plain] with [password] (not empty). */
    fun encrypt(plain: ByteArray, password: CharArray): ByteArray = encrypt(plain, password, ITERATIONS)

    internal fun encrypt(plain: ByteArray, password: CharArray, iterations: Int): ByteArray {
        require(password.isNotEmpty()) { "empty password" }
        require(iterations in 1..MAX_ITERATIONS) { "iterations" }
        val salt = ByteArray(SALT).also(random::nextBytes)
        val nonce = ByteArray(NONCE).also(random::nextBytes)
        val pw = utf8(password) ?: throw IllegalArgumentException("password is not valid text")
        val master = try { pbkdf2(pw, salt, iterations) } finally { pw.fill(0) }
        val key = hmac(master, "RMBK1 key")
        val check = hmac(master, "RMBK1 check")
        master.fill(0)
        try {
            val header = ByteBuffer.allocate(HEADER)
                .put(magic).put(VERSION.toByte()).putInt(iterations).put(salt).put(nonce)
                .putLong(plain.size.toLong()).put(check, 0, CHECK).array()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val k = RawKey(key, "AES")
            try {
                cipher.init(Cipher.ENCRYPT_MODE, k, GCMParameterSpec(TAG * 8, nonce))
            } finally {
                k.wipe()
            }
            cipher.updateAAD(header)
            val sealed = cipher.doFinal(plain)
            return header + sealed
        } finally {
            key.fill(0)
            check.fill(0)
        }
    }

    /** Opens a file written by [encrypt]. Throws [WrongPassword] or [Damaged]. */
    fun decrypt(data: ByteArray, password: CharArray): ByteArray {
        if (data.size < HEADER + TAG || !isEncrypted(data)) throw Damaged()
        val buf = ByteBuffer.wrap(data)
        buf.position(magic.size)
        if (buf.get().toInt() != VERSION) throw Damaged()
        val iterations = buf.getInt().toLong() and 0xFFFF_FFFFL
        if (iterations !in 1..MAX_ITERATIONS) throw Damaged()
        val salt = ByteArray(SALT).also { buf.get(it) }
        val nonce = ByteArray(NONCE).also { buf.get(it) }
        val length = buf.getLong()
        if (length < 0 || length != (data.size - HEADER - TAG).toLong()) throw Damaged()
        val stored = ByteArray(CHECK).also { buf.get(it) }
        if (password.isEmpty()) throw WrongPassword()
        val pw = utf8(password) ?: throw WrongPassword()
        val master = try { pbkdf2(pw, salt, iterations.toInt()) } finally { pw.fill(0) }
        val key = hmac(master, "RMBK1 key")
        val check = hmac(master, "RMBK1 check")
        master.fill(0)
        try {
            var diff = 0
            for (i in 0 until CHECK) diff = diff or (check[i].toInt() xor stored[i].toInt())
            if (diff != 0) throw WrongPassword()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val k = RawKey(key, "AES")
            try {
                cipher.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(TAG * 8, nonce))
            } finally {
                k.wipe()
            }
            cipher.updateAAD(data, 0, HEADER)
            return try {
                cipher.doFinal(data, HEADER, data.size - HEADER)
            } catch (_: AEADBadTagException) {
                throw Damaged()
            } catch (_: GeneralSecurityException) {
                throw Damaged()
            }
        } finally {
            key.fill(0)
            check.fill(0)
        }
    }

    /** The UTF-8 bytes of [password] without making a String; null for unpaired surrogates. */
    private fun utf8(password: CharArray): ByteArray? {
        val encoder = Charsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val out = try { encoder.encode(CharBuffer.wrap(password)) } catch (_: CharacterCodingException) { return null }
        val bytes = ByteArray(out.remaining())
        out.get(bytes)
        if (out.hasArray()) out.array().fill(0)
        return bytes
    }

    /** PBKDF2-HMAC-SHA256 (RFC 8018 §5.2), one 32-byte block. */
    internal fun pbkdf2(password: ByteArray, salt: ByteArray, iterations: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        val k = RawKey(password, "HmacSHA256")
        try {
            mac.init(k)
        } finally {
            k.wipe()
        }
        mac.update(salt)
        mac.update(byteArrayOf(0, 0, 0, 1))
        val u = mac.doFinal()
        val t = u.copyOf()
        for (i in 1 until iterations) {
            mac.update(u)
            mac.doFinal(u, 0)
            for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
        }
        u.fill(0)
        return t
    }

    private fun hmac(key: ByteArray, label: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        val k = RawKey(key, "HmacSHA256")
        try {
            mac.init(k)
        } finally {
            k.wipe()
        }
        return mac.doFinal(label.toByteArray(Charsets.US_ASCII))
    }

    /**
     * A raw key that hands out copies and can be wiped (a SecretKeySpec keeps its own copy until it is
     * collected). Providers copy the key material when they are initialised.
     */
    private class RawKey(source: ByteArray, private val algorithm: String) : SecretKey {
        private val bytes = source.copyOf()
        fun wipe() = bytes.fill(0)
        override fun getAlgorithm() = algorithm
        override fun getFormat() = "RAW"
        override fun getEncoded(): ByteArray = bytes.copyOf()
        override fun destroy() = wipe()
        override fun isDestroyed() = bytes.all { it == 0.toByte() }
    }
}

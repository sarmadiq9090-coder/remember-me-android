package com.fastkiwi.rememberme.data

import com.fastkiwi.rememberme.core.AppState
import com.fastkiwi.rememberme.core.T
import com.fastkiwi.rememberme.core.TaskInput
import com.fastkiwi.rememberme.core.Tasks
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.time.Instant
import java.time.ZoneId
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlin.random.Random

/** The password-encrypted backup (F-086). */
class BackupCryptoTest {
    @Before fun ids() = T.ids()

    private val now = Instant.parse("2026-11-15T07:00:00Z")
    private val password = "كلمة سر 123".toCharArray()

    private fun backupZip(): ByteArray {
        val zone = ZoneId.of("Asia/Baghdad")
        val (s, _) = Tasks.save(AppState(), TaskInput(title = "SECRET-MARKER دفع الإيجار", body = "x".repeat(2_000), dueAt = now.plusSeconds(3_600)), now, zone)
        return ByteArrayOutputStream().also { Backup.export(s, it, now) }.toByteArray()
    }

    private fun contains(hay: ByteArray, needle: ByteArray): Boolean =
        (0..hay.size - needle.size).any { i -> needle.indices.all { hay[i + it] == needle[it] } }

    @Test fun roundTripWithTheDefaultParameters() {
        val zip = backupZip()
        val sealed = BackupCrypto.encrypt(zip, password)
        assertTrue(BackupCrypto.isEncrypted(sealed))
        assertEquals(BackupCrypto.HEADER + zip.size + 16, sealed.size)
        val h = ByteBuffer.wrap(sealed)
        assertEquals("RMBK1", String(sealed, 0, 5, Charsets.US_ASCII))
        assertEquals(1, sealed[5].toInt())
        assertEquals(600_000, h.getInt(6))
        assertEquals(zip.size.toLong(), h.getLong(38))
        val opened = BackupCrypto.decrypt(sealed, password)
        assertArrayEquals(zip, opened)
        // The caller's password is not changed (the caller clears it).
        assertArrayEquals("كلمة سر 123".toCharArray(), password)
        // And the result reads as a backup again.
        assertEquals("SECRET-MARKER دفع الإيجار", Backup.read(opened.inputStream(), now).state.tasks.single().title)
        // Random salt and nonce: the same backup never encrypts to the same bytes.
        val again = BackupCrypto.encrypt(zip, password, 1_000)
        val third = BackupCrypto.encrypt(zip, password, 1_000)
        assertFalse(again.copyOfRange(10, 38).contentEquals(third.copyOfRange(10, 38)))
        assertFalse(again.copyOfRange(BackupCrypto.HEADER, again.size).contentEquals(third.copyOfRange(BackupCrypto.HEADER, third.size)))
    }

    @Test fun emptyAndLargePlaintexts() {
        for (plain in listOf(ByteArray(0), Random(1).nextBytes(3 * 1024 * 1024))) {
            val sealed = BackupCrypto.encrypt(plain, "p".toCharArray(), 1_000)
            assertArrayEquals(plain, BackupCrypto.decrypt(sealed, "p".toCharArray()))
        }
    }

    @Test fun aWrongPasswordIsRefused() {
        val sealed = BackupCrypto.encrypt(backupZip(), password)
        for (wrong in listOf("كلمة سر 124", "كلمة سر 123 ", "", "\uD800")) {
            assertThrows(wrong, BackupCrypto.WrongPassword::class.java) { BackupCrypto.decrypt(sealed, wrong.toCharArray()) }
        }
        assertThrows(IllegalArgumentException::class.java) { BackupCrypto.encrypt(ByteArray(10), CharArray(0)) }
    }

    @Test fun aChangedByteAnywhereIsRefusedNeverGarbage() {
        val plain = "SECRET-MARKER ".repeat(20).toByteArray()
        val sealed = BackupCrypto.encrypt(plain, password, 1_000)
        val seen = HashSet<String>()
        for (i in sealed.indices) for (mask in listOf(0x01, 0xFF)) {
            val bad = sealed.copyOf()
            bad[i] = (bad[i].toInt() xor mask).toByte()
            try {
                BackupCrypto.decrypt(bad, password)
                fail("byte $i ^ $mask was accepted")
            } catch (e: BackupCrypto.Damaged) {
                seen += "damaged"
            } catch (e: BackupCrypto.WrongPassword) {
                seen += "wrong"
            }
        }
        assertEquals(setOf("damaged", "wrong"), seen)
        // The ciphertext and tag are Damaged (the key check passed), not a wrong password.
        for (i in listOf(BackupCrypto.HEADER, BackupCrypto.HEADER + 100, sealed.size - 1)) {
            val bad = sealed.copyOf().also { it[i] = (it[i].toInt() xor 0x10).toByte() }
            assertThrows(BackupCrypto.Damaged::class.java) { BackupCrypto.decrypt(bad, password) }
        }
    }

    @Test fun aTruncatedOrExtendedFileIsDamaged() {
        val sealed = BackupCrypto.encrypt("hello backup".repeat(30).toByteArray(), password, 1_000)
        for (n in 0 until sealed.size) {
            assertThrows("length $n", BackupCrypto.Damaged::class.java) { BackupCrypto.decrypt(sealed.copyOf(n), password) }
        }
        assertThrows(BackupCrypto.Damaged::class.java) { BackupCrypto.decrypt(sealed + byteArrayOf(0), password) }
    }

    @Test fun theIterationsInTheHeaderAreHonoured() {
        val plain = "data".toByteArray()
        val sealed = BackupCrypto.encrypt(plain, password, 1_234)
        assertEquals(1_234, ByteBuffer.wrap(sealed).getInt(6))
        assertArrayEquals(plain, BackupCrypto.decrypt(sealed, password))
        // Another count derives another key: refused.
        val other = sealed.copyOf().also { ByteBuffer.wrap(it).putInt(6, 1_235) }
        assertThrows(BackupCrypto.WrongPassword::class.java) { BackupCrypto.decrypt(other, password) }
        // Out of the allowed range: damaged, without running the key derivation.
        for (n in listOf(0, -1, BackupCrypto.MAX_ITERATIONS + 1)) {
            val bad = sealed.copyOf().also { ByteBuffer.wrap(it).putInt(6, n) }
            assertThrows(BackupCrypto.Damaged::class.java) { BackupCrypto.decrypt(bad, password) }
        }
        // A file from a later version with a higher count opens (the count can grow).
        val stronger = BackupCrypto.encrypt(plain, password, 700_000)
        assertArrayEquals(plain, BackupCrypto.decrypt(stronger, password))
    }

    @Test fun theKeyDerivationIsStandardPbkdf2() {
        val salt = ByteArray(16) { it.toByte() }
        for (pw in listOf("password", "كلمة سر", "ä€😀")) {
            val ours = BackupCrypto.pbkdf2(pw.toByteArray(Charsets.UTF_8), salt, 2_000)
            val jdk = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(pw.toCharArray(), salt, 2_000, 256)).encoded
            assertArrayEquals(pw, jdk, ours)
        }
    }

    @Test fun thePlaintextNeverAppearsInTheOutput() {
        val zip = backupZip()
        val sealed = BackupCrypto.encrypt(zip, password)
        assertFalse(contains(sealed, "SECRET-MARKER".toByteArray()))
        assertFalse(contains(sealed, "data.json".toByteArray()))
        assertFalse(contains(sealed, zip.copyOfRange(0, 16)))
        assertFalse(contains(sealed, "كلمة سر".toByteArray(Charsets.UTF_8)))
    }

    @Test fun aPlainBackupIsNotEncrypted() {
        val zip = backupZip()
        assertFalse(BackupCrypto.isEncrypted(zip))
        assertFalse(BackupCrypto.isEncrypted(ByteArray(0)))
        assertFalse(BackupCrypto.isEncrypted("RMBK".toByteArray()))
        assertFalse(BackupCrypto.isEncrypted("{\"version\":1}".toByteArray()))
        assertThrows(BackupCrypto.Damaged::class.java) { BackupCrypto.decrypt(zip, password) }
        // Right magic, other version: damaged.
        val sealed = BackupCrypto.encrypt(zip, password, 1_000)
        sealed[5] = 2
        assertThrows(BackupCrypto.Damaged::class.java) { BackupCrypto.decrypt(sealed, password) }
    }
}

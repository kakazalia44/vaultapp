package com.example.vault

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import kotlin.random.Random

class VaultCryptoTest {

    private fun setMaster(key: ByteArray) {
        val f = VaultCrypto::class.java.getDeclaredField("master")
        f.isAccessible = true
        f.set(null, key)
    }

    @Before
    fun setup() {
        setMaster(ByteArray(32) { it.toByte() })
    }

    private fun encrypt(data: ByteArray): File {
        val f = File.createTempFile("vault", ".enc")
        f.deleteOnExit()
        f.outputStream().use { VaultCrypto.encryptStream(ByteArrayInputStream(data), data.size.toLong(), it) }
        return f
    }

    private fun readAll(f: File): ByteArray = VaultCrypto.openStream(f).use { it.readBytes() }

    @Test
    fun roundTripManySizes() {
        for (n in listOf(0, 1, 15, 16, 17, 65535, 65536, 65537, 131072, 200_001)) {
            val data = Random(n).nextBytes(n)
            val f = encrypt(data)
            assertTrue("зашифрован, размер $n", VaultCrypto.isEncrypted(f))
            assertEquals("длина данных, размер $n", n.toLong(), VaultCrypto.plainLength(f))
            assertArrayEquals("содержимое, размер $n", data, readAll(f))
        }
    }

    @Test
    fun ciphertextDoesNotContainPlaintext() {
        val data = ByteArray(100_000) { 'A'.code.toByte() }
        val raw = encrypt(data).readBytes()
        var run = 0
        var longest = 0
        for (b in raw) {
            if (b == 'A'.code.toByte()) { run++; if (run > longest) longest = run } else run = 0
        }
        assertTrue("в шифртексте не должно быть длинных повторов открытого текста", longest < 16)
    }

    @Test
    fun randomAccessReads() {
        val data = Random(7).nextBytes(300_000)
        val f = encrypt(data)
        val r = VaultCrypto.openReader(f)
        val rnd = Random(99)
        repeat(300) {
            val pos = rnd.nextInt(0, data.size)
            val len = rnd.nextInt(1, 150_000)
            val buf = ByteArray(len)
            val n = r.read(pos.toLong(), buf, 0, len)
            val expected = minOf(len, data.size - pos)
            assertEquals(expected, n)
            assertArrayEquals(data.copyOfRange(pos, pos + expected), buf.copyOf(n))
        }
        assertEquals(-1, r.read(data.size.toLong(), ByteArray(10), 0, 10))
        r.close()
    }

    private fun assertReadFails(f: File, message: String) {
        try {
            readAll(f)
            fail(message)
        } catch (e: Exception) {
            // ожидаемо
        }
    }

    @Test
    fun tamperedByteIsDetected() {
        val f = encrypt(Random(1).nextBytes(150_000))
        RandomAccessFile(f, "rw").use { r ->
            r.seek(70_000)
            val b = r.read()
            r.seek(70_000)
            r.write(b xor 1)
        }
        assertReadFails(f, "изменённый байт не обнаружен")
    }

    @Test
    fun truncatedFileIsDetected() {
        val f = encrypt(Random(2).nextBytes(150_000))
        RandomAccessFile(f, "rw").use { it.setLength(f.length() - 100) }
        assertReadFails(f, "обрезанный файл не обнаружен")
    }

    @Test
    fun changedLengthInHeaderIsDetected() {
        val f = encrypt(Random(3).nextBytes(150_000))
        RandomAccessFile(f, "rw").use { r ->
            r.seek(31)
            val b = r.read()
            r.seek(31)
            r.write(b xor 1)
        }
        assertReadFails(f, "подмена длины не обнаружена")
    }

    @Test
    fun wrongKeyCannotRead() {
        val data = Random(4).nextBytes(10_000)
        val f = encrypt(data)
        setMaster(ByteArray(32) { (it + 1).toByte() })
        assertReadFails(f, "чужой ключ подошёл")
        setMaster(ByteArray(32) { it.toByte() })
        assertArrayEquals(data, readAll(f))
    }

    @Test
    fun sameDataEncryptedTwiceDiffers() {
        val data = Random(5).nextBytes(5_000)
        assertFalse(encrypt(data).readBytes().contentEquals(encrypt(data).readBytes()))
    }

    @Test
    fun plainFileIsNotTakenForEncrypted() {
        val f = File.createTempFile("plain", ".bin")
        f.deleteOnExit()
        f.writeBytes(ByteArray(1000) { 5 })
        assertFalse(VaultCrypto.isEncrypted(f))
    }
}

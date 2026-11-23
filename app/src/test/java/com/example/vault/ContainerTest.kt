package com.example.vault

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.random.Random

class ContainerTest {

    private fun tmpDir(): File = Files.createTempDirectory("ct").toFile().also { it.deleteOnExit() }

    private fun setMaster(key: ByteArray?) {
        val f = VaultCrypto::class.java.getDeclaredField("master")
        f.isAccessible = true
        f.set(null, key)
    }

    private fun vectorFile(): File {
        val bytes = javaClass.getResourceAsStream("/com/example/vault/vector.bin")!!.readBytes()
        val f = File.createTempFile("vector", ".bin")
        f.deleteOnExit()
        f.writeBytes(bytes)
        return f
    }

    private val vectorData = ByteArray(70000) { ((it * 31) % 251).toByte() }

    /** Контейнер создан отдельной программой на Python: проверяем, что формат читается одинаково. */
    @Test
    fun readsContainerMadeByPython() {
        val dest = tmpDir()
        val n = Container.import(vectorFile(), "тест-пароль-123", dest, false) {}
        assertEquals(3, n)
        assertEquals("Привет, мир! 😀\n", File(dest, "hello.txt").readText())
        assertArrayEquals(vectorData, File(dest, "sub/data.bin").readBytes())
        assertEquals("emoji", File(dest, "😀 имя.txt").readText())
        assertTrue(File(dest, "sub/empty-dir").isDirectory)
        assertEquals(1700000002000L, File(dest, "sub/data.bin").lastModified())
    }

    @Test
    fun wrongPasswordIsRejected() {
        try {
            Container.import(vectorFile(), "не тот пароль", tmpDir(), false) {}
            fail("неверный пароль не отвергнут")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("пароль"))
        }
    }

    @Test
    fun importIntoVaultEncryptsFiles() {
        setMaster(ByteArray(32) { it.toByte() })
        try {
            val dest = tmpDir()
            Container.import(vectorFile(), "тест-пароль-123", dest, true) {}
            val enc = File(dest, "sub/data.bin")
            assertTrue(VaultCrypto.isEncrypted(enc))
            assertEquals(70000L, VaultCrypto.plainLength(enc))
            assertArrayEquals(vectorData, VaultCrypto.openStream(enc).use { it.readBytes() })
        } finally {
            setMaster(null)
        }
    }

    @Test
    fun exportThenImportRoundTrip() {
        val src = tmpDir()
        File(src, "a.txt").writeText("первый файл")
        File(src, "dir/глубже").mkdirs()
        File(src, "dir/глубже/big.bin").writeBytes(Random(5).nextBytes(200_000))
        File(src, "dir/empty").mkdirs()
        File(src, "😀.txt").writeText("emoji")
        File(src, "zero.bin").writeBytes(ByteArray(0))
        File(src, "a.txt").setLastModified(1600000000000L)

        val container = File.createTempFile("backup", ".dat")
        container.deleteOnExit()
        var lastPct = 0
        Container.export(Container.collect(src), "правильный-пароль-1", container) { lastPct = it }
        assertEquals(100, lastPct)

        val dest = tmpDir()
        val n = Container.import(container, "правильный-пароль-1", dest, false) {}
        assertEquals(4, n)
        assertEquals("первый файл", File(dest, "a.txt").readText())
        assertArrayEquals(File(src, "dir/глубже/big.bin").readBytes(), File(dest, "dir/глубже/big.bin").readBytes())
        assertTrue(File(dest, "dir/empty").isDirectory)
        assertEquals("emoji", File(dest, "😀.txt").readText())
        assertEquals(0L, File(dest, "zero.bin").length())
        assertEquals(1600000000000L, File(dest, "a.txt").lastModified())

        // повторная загрузка не перезаписывает, а добавляет номер
        Container.import(container, "правильный-пароль-1", dest, false) {}
        assertEquals("первый файл", File(dest, "a (1).txt").readText())
    }

    @Test
    fun containerDoesNotRevealNames() {
        val src = tmpDir()
        File(src, "секретное-имя.txt").writeText("данные данные данные")
        val container = File.createTempFile("backup", ".dat")
        container.deleteOnExit()
        Container.export(Container.collect(src), "правильный-пароль-2", container) {}
        val raw = String(container.readBytes(), Charsets.ISO_8859_1)
        assertFalse(raw.contains("данные"))
        assertFalse(container.readBytes().toList().windowed(4).any { it == "name".toByteArray().toList() })
    }

    @Test
    fun pathTraversalIsNeutralized() {
        val src = tmpDir()
        val f = File(src, "x.txt")
        f.writeText("evil")
        val container = File.createTempFile("backup", ".dat")
        container.deleteOnExit()
        val entries = listOf(Container.Entry("../../escape.txt", f, 4L, 1L))
        Container.export(entries, "правильный-пароль-3", container) {}
        val parent = tmpDir()
        val dest = File(parent, "inside").also { it.mkdirs() }
        Container.import(container, "правильный-пароль-3", dest, false) {}
        assertTrue(File(dest, "escape.txt").exists())
        assertFalse(File(parent, "escape.txt").exists())
    }

    @Test
    fun brokenContainerIsReported() {
        val f = File.createTempFile("bad", ".dat")
        f.deleteOnExit()
        f.writeBytes(ByteArray(100) { 7 })
        try {
            Container.import(f, "любой-пароль-1", tmpDir(), false) {}
            fail("мусор принят за контейнер")
        } catch (e: IOException) {
            // ожидаемо
        }
    }
}

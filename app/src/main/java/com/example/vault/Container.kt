package com.example.vault

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Collections
import javax.crypto.AEADBadTagException
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Контейнер для выгрузки хранилища: один файл, зашифрованный паролем.
 *
 * Файл: "PRVCNT01" (8) | соль (16) | число повторов PBKDF2 uint32 (4) | длина данных uint64 (8) | блоки.
 * Ключ AES-256 = PBKDF2-HMAC-SHA256(пароль в UTF-8, соль, повторы). Блоки по 64 КБ, AES-GCM,
 * nonce = 4 нуля + номер блока (uint64), в проверке подлинности весь заголовок (36 байт).
 * Данные (все числа big-endian): int32 число записей; для каждой записи: uint16 длина пути,
 * путь в UTF-8, int64 дата изменения (мс), int64 размер (-1 для папки), затем сами данные.
 */
object Container {
    const val ITER = 600_000
    private val MAGIC = "PRVCNT01".toByteArray(Charsets.US_ASCII)
    private const val HDR = 36

    class Entry(val path: String, val file: File?, val size: Long, val mtime: Long)

    fun collect(root: File): List<Entry> {
        val out = mutableListOf<Entry>()
        fun walk(d: File, prefix: String) {
            d.listFiles()?.sortedBy { it.name }?.forEach { f ->
                if (f.name.endsWith(".enc.tmp")) return@forEach
                val rel = if (prefix.isEmpty()) f.name else prefix + "/" + f.name
                if (f.isDirectory) {
                    out.add(Entry(rel, null, -1L, f.lastModified()))
                    walk(f, rel)
                } else if (f.isFile) {
                    out.add(Entry(rel, f, Fs.sizeOf(f), f.lastModified()))
                }
            }
        }
        walk(root, "")
        return out
    }

    /** PBKDF2-HMAC-SHA256, один блок на 32 байта; пароль берётся как байты UTF-8, без неоднозначностей. */
    internal fun deriveKey(password: String, salt: ByteArray, iterations: Int): SecretKeySpec {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        mac.update(salt)
        mac.update(byteArrayOf(0, 0, 0, 1))
        var u = mac.doFinal()
        val t = u.copyOf()
        for (i in 1 until iterations) {
            u = mac.doFinal(u)
            for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
        }
        return SecretKeySpec(t, "AES")
    }

    private fun meta(e: Entry): ByteArray {
        val pb = e.path.toByteArray(Charsets.UTF_8)
        if (pb.size > 65535) throw IOException("Слишком длинный путь: " + e.path)
        val bb = ByteBuffer.allocate(2 + pb.size + 16)
        bb.putShort((pb.size and 0xFFFF).toShort())
        bb.put(pb)
        bb.putLong(e.mtime)
        bb.putLong(e.size)
        return bb.array()
    }

    /** Отдаёт ровно size байт; если файл оказался короче, бросает исключение. */
    private class ExactInputStream(
        private val inner: InputStream,
        private var left: Long,
        private val closeInner: Boolean
    ) : InputStream() {
        override fun read(): Int {
            val b = ByteArray(1)
            val n = read(b, 0, 1)
            return if (n <= 0) -1 else b[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (left <= 0) return -1
            val n = inner.read(b, off, minOf(len.toLong(), left).toInt())
            if (n < 0) throw IOException("Файл оказался короче, чем ожидалось")
            left -= n
            return n
        }

        override fun close() {
            if (closeInner) inner.close()
        }
    }

    private class CountingInputStream(
        private val inner: InputStream,
        private val onBytes: (Long) -> Unit
    ) : InputStream() {
        private var total = 0L
        override fun read(): Int {
            val v = inner.read()
            if (v >= 0) report(1)
            return v
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = inner.read(b, off, len)
            if (n > 0) report(n)
            return n
        }

        private fun report(n: Int) {
            total += n
            onBytes(total)
        }

        override fun close() = inner.close()
    }

    /** Записывает контейнер со всем списком entries в файл dest. onProgress получает проценты 0..100. */
    fun export(entries: List<Entry>, password: String, dest: File, onProgress: (Int) -> Unit) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val metas = entries.map { meta(it) }
        var total = 4L
        for (i in entries.indices) total += metas[i].size + maxOf(entries[i].size, 0L)

        val key = deriveKey(password, salt, ITER)
        val header = ByteBuffer.allocate(HDR).put(MAGIC).put(salt).putInt(ITER).putLong(total).array()

        val parts = sequence<InputStream> {
            yield(java.io.ByteArrayInputStream(ByteBuffer.allocate(4).putInt(entries.size).array()))
            for (i in entries.indices) {
                yield(java.io.ByteArrayInputStream(metas[i]))
                val e = entries[i]
                if (e.size > 0 && e.file != null) {
                    yield(ExactInputStream(Fs.openInput(e.file), e.size, true))
                }
            }
        }.iterator()
        val enumeration = object : java.util.Enumeration<InputStream> {
            override fun hasMoreElements(): Boolean = parts.hasNext()
            override fun nextElement(): InputStream = parts.next()
        }

        var lastPct = -1
        val source = CountingInputStream(java.io.SequenceInputStream(enumeration)) { done ->
            val pct = (done * 100 / total).toInt()
            if (pct != lastPct) {
                lastPct = pct
                onProgress(pct)
            }
        }
        try {
            dest.outputStream().buffered(VaultCrypto.CHUNK).use { out ->
                out.write(header)
                source.use { VaultCrypto.encryptChunks(key, header, it, total, out) }
            }
        } catch (e: Exception) {
            dest.delete()
            throw e
        }
    }

    private fun cleanPath(p: String): List<String> =
        p.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." && it != ".." }

    /**
     * Разворачивает контейнер в папку destRoot. Если encrypt = true, файлы сразу шифруются ключом
     * хранилища. Существующие файлы не перезаписываются: к имени добавляется номер.
     * Возвращает число восстановленных файлов.
     */
    fun import(src: File, password: String, destRoot: File, encrypt: Boolean, onProgress: (Int) -> Unit): Int {
        RandomAccessFile(src, "r").use { raf ->
            val h = ByteArray(HDR)
            try {
                raf.readFully(h)
            } catch (e: Exception) {
                throw IOException("Это не контейнер или файл повреждён")
            }
            if (!h.copyOfRange(0, 8).contentEquals(MAGIC)) throw IOException("Это не контейнер или файл повреждён")
            val bb = ByteBuffer.wrap(h)
            val salt = h.copyOfRange(8, 24)
            val iter = bb.getInt(24)
            val plainLen = bb.getLong(28)
            if (iter < 1000 || iter > 20_000_000 || plainLen < 4) throw IOException("Файл повреждён")

            val key = deriveKey(password, salt, iter)
            val reader = VaultReader(raf, h, plainLen, key, HDR)
            val inp = DataInputStream(BufferedInputStream(VaultInputStream(reader), VaultCrypto.CHUNK))
            var files = 0
            var lastPct = -1
            try {
                val count = inp.readInt()
                if (count < 0 || count > 5_000_000) throw IOException("Файл повреждён")
                var consumed = 4L
                for (i in 0 until count) {
                    val plen = inp.readUnsignedShort()
                    val pb = ByteArray(plen)
                    inp.readFully(pb)
                    val mtime = inp.readLong()
                    val size = inp.readLong()
                    consumed += 2 + plen + 16 + maxOf(size, 0L)
                    val parts = cleanPath(String(pb, Charsets.UTF_8))
                    if (parts.isEmpty()) {
                        if (size > 0) throw IOException("Файл повреждён")
                        continue
                    }
                    if (size < 0) {
                        var d = destRoot
                        for (p in parts) d = File(d, p)
                        d.mkdirs()
                        continue
                    }
                    var parent = destRoot
                    for (p in parts.dropLast(1)) parent = File(parent, p)
                    parent.mkdirs()
                    val dest = Fs.uniqueDest(parent, parts.last())
                    try {
                        val data = ExactInputStream(inp, size, false)
                        if (encrypt) {
                            dest.outputStream().buffered(VaultCrypto.CHUNK).use { o ->
                                VaultCrypto.encryptStream(data, size, o)
                            }
                        } else {
                            dest.outputStream().buffered(VaultCrypto.CHUNK).use { o -> data.copyTo(o, VaultCrypto.CHUNK) }
                        }
                        dest.setLastModified(mtime)
                    } catch (e: Exception) {
                        dest.delete()
                        throw e
                    }
                    files++
                    val pct = (consumed * 100 / plainLen).toInt().coerceIn(0, 100)
                    if (pct != lastPct) {
                        lastPct = pct
                        onProgress(pct)
                    }
                }
            } catch (e: AEADBadTagException) {
                throw IOException("Неверный пароль или файл повреждён")
            }
            return files
        }
    }
}

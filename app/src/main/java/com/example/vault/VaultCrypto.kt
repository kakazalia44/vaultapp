package com.example.vault

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Шифрование хранилища.
 *
 * Формат файла: заголовок 32 байта (метка PRVENC01, случайная «соль» 16 байт, длина данных 8 байт),
 * затем блоки по 64 КБ, каждый зашифрован AES-256-GCM. Ключ файла получается из главного ключа
 * и «соли» файла, поэтому файлы можно свободно переименовывать и переносить внутри хранилища.
 * Заголовок входит в проверку подлинности каждого блока: подмена или обрезка файла обнаруживается.
 *
 * Главный ключ хранится завёрнутым в два слоя: ключ из PIN (PBKDF2) и ключ защищённого
 * хранилища Android (Keystore). В памяти он находится только пока хранилище открыто.
 */
object VaultCrypto {
    internal const val CHUNK = 65536
    internal const val TAG = 16
    internal const val HEADER = 32
    internal val MAGIC = "PRVENC01".toByteArray(Charsets.US_ASCII)
    private const val KS_ALIAS = "vault_wrap"
    private const val TMP_SUFFIX = ".enc.tmp"

    var rootPath: String = ""
        private set

    @Volatile
    private var master: ByteArray? = null

    var unlocked by mutableStateOf(false)
        private set

    fun init(ctx: Context) {
        rootPath = Fs.vaultRoot(ctx).path
    }

    fun isVaultPath(f: File): Boolean =
        rootPath.isNotEmpty() && (f.path == rootPath || f.path.startsWith("$rootPath/"))

    fun lock() {
        val m = master
        master = null
        m?.fill(0)
        unlocked = false
    }

    // ───────────── ключи ─────────────

    private fun ksKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore")
        ks.load(null)
        val existing = ks.getKey(KS_ALIAS, null)
        if (existing is SecretKey) return existing
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(
            KeyGenParameterSpec.Builder(
                KS_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return kg.generateKey()
    }

    private fun pinKey(pin: String, salt: ByteArray): SecretKeySpec {
        val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val bytes = f.generateSecret(PBEKeySpec(pin.toCharArray(), salt, 400_000, 256)).encoded
        return SecretKeySpec(bytes, "AES")
    }

    private fun wrapKeystore(key: SecretKey, data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key)
        return c.iv + c.doFinal(data)
    }

    private fun unwrapKeystore(key: SecretKey, blob: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob.copyOfRange(0, 12)))
        return c.doFinal(blob, 12, blob.size - 12)
    }

    private fun wrapSoft(key: SecretKeySpec, data: ByteArray): ByteArray {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        return iv + c.doFinal(data)
    }

    private fun unwrapSoft(key: SecretKeySpec, blob: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob.copyOfRange(0, 12)))
        return c.doFinal(blob, 12, blob.size - 12)
    }

    /**
     * Открывает хранилище для PIN, который уже проверен. Если главного ключа ещё нет,
     * создаёт его. Блокирующий вызов (около полусекунды).
     */
    fun unlock(ctx: Context, pin: String): Boolean {
        val p = ctx.getSharedPreferences("vault_keys", Context.MODE_PRIVATE)
        return try {
            val wrapped = p.getString("wrapped", null)
            val saltS = p.getString("salt", null)
            val m: ByteArray
            if (wrapped == null || saltS == null) {
                m = ByteArray(32).also { SecureRandom().nextBytes(it) }
                val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
                val inner = wrapSoft(pinKey(pin, salt), m)
                val outer = wrapKeystore(ksKey(), inner)
                val saved = p.edit()
                    .putString("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
                    .putString("wrapped", Base64.encodeToString(outer, Base64.NO_WRAP))
                    .commit()
                if (!saved) throw IOException("не удалось сохранить ключ")
            } else {
                val inner = unwrapKeystore(ksKey(), Base64.decode(wrapped, Base64.NO_WRAP))
                m = unwrapSoft(pinKey(pin, Base64.decode(saltS, Base64.NO_WRAP)), inner)
            }
            master = m
            unlocked = true
            true
        } catch (e: Exception) {
            false
        }
    }

    internal fun fileKey(m: ByteArray, salt: ByteArray): SecretKeySpec {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(m, "HmacSHA256"))
        mac.update("file".toByteArray(Charsets.US_ASCII))
        mac.update(salt)
        return SecretKeySpec(mac.doFinal(), "AES")
    }

    internal fun nonce(index: Long): ByteArray =
        ByteBuffer.allocate(12).putInt(0).putLong(index).array()

    // ───────────── шифрование и чтение ─────────────

    fun isEncrypted(f: File): Boolean {
        if (!f.isFile || f.length() < HEADER) return false
        return try {
            RandomAccessFile(f, "r").use { r ->
                val h = ByteArray(MAGIC.size)
                r.readFully(h)
                h.contentEquals(MAGIC)
            }
        } catch (e: Exception) {
            false
        }
    }

    /** Размер данных в зашифрованном файле, посчитанный по размеру самого файла. */
    fun plainLength(f: File): Long {
        val body = f.length() - HEADER
        if (body <= 0) return 0
        val full = body / (CHUNK + TAG)
        val rem = body % (CHUNK + TAG)
        return full * CHUNK + (if (rem > TAG) rem - TAG else 0)
    }

    fun encryptStream(input: InputStream, length: Long, out: OutputStream) {
        val m = master ?: throw IOException("Хранилище закрыто")
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val header = ByteBuffer.allocate(HEADER).put(MAGIC).put(salt).putLong(length).array()
        out.write(header)
        val key = fileKey(m, salt)
        val buf = ByteArray(CHUNK)
        var index = 0L
        var left = length
        while (left > 0) {
            val want = minOf(CHUNK.toLong(), left).toInt()
            var got = 0
            while (got < want) {
                val n = input.read(buf, got, want - got)
                if (n < 0) throw IOException("Файл оказался короче, чем ожидалось")
                got += n
            }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce(index)))
            c.updateAAD(header)
            out.write(c.doFinal(buf, 0, want))
            index++
            left -= want
        }
    }

    fun encryptFile(src: File, dest: File) {
        try {
            src.inputStream().use { i ->
                dest.outputStream().buffered(CHUNK).use { o -> encryptStream(i, src.length(), o) }
            }
        } catch (e: Exception) {
            dest.delete()
            throw e
        }
    }

    fun decryptFile(src: File, dest: File) {
        try {
            openStream(src).use { i ->
                dest.outputStream().buffered(CHUNK).use { o -> i.copyTo(o, CHUNK) }
            }
        } catch (e: Exception) {
            dest.delete()
            throw e
        }
    }

    fun openReader(f: File): VaultReader {
        val m = master ?: throw IOException("Хранилище закрыто")
        val raf = RandomAccessFile(f, "r")
        try {
            val h = ByteArray(HEADER)
            raf.readFully(h)
            if (!h.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
                throw IOException("Файл не зашифрован")
            }
            val len = ByteBuffer.wrap(h, 24, 8).long
            return VaultReader(raf, h, len, fileKey(m, h.copyOfRange(8, 24)))
        } catch (e: Exception) {
            raf.close()
            throw e
        }
    }

    fun openStream(f: File): InputStream = VaultInputStream(openReader(f))

    // ───────────── перевод старых файлов ─────────────

    private fun walk(d: File): List<File> {
        val out = mutableListOf<File>()
        d.listFiles()?.forEach { f -> if (f.isDirectory) out.addAll(walk(f)) else out.add(f) }
        return out
    }

    private fun cleanTemp() {
        walk(File(rootPath)).filter { it.name.endsWith(TMP_SUFFIX) }.forEach { it.delete() }
    }

    fun needsMigration(): Boolean {
        cleanTemp()
        return walk(File(rootPath)).any { !isEncrypted(it) }
    }

    /** Шифрует файлы, которые лежали в хранилище без шифрования. */
    fun migrate(onProgress: (Int, Int) -> Unit) {
        val todo = walk(File(rootPath)).filter { !isEncrypted(it) }
        todo.forEachIndexed { i, f ->
            onProgress(i, todo.size)
            val tmp = File(f.parentFile, f.name + TMP_SUFFIX)
            val mt = f.lastModified()
            try {
                encryptFile(f, tmp)
                tmp.setLastModified(mt)
                if (!tmp.renameTo(f)) throw IOException("не удалось заменить файл")
            } catch (e: Exception) {
                tmp.delete()
                throw e
            }
        }
    }

    // ───────────── кадр из видео ─────────────

    fun videoThumb(f: File): Bitmap? {
        val reader = openReader(f)
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(VaultMediaDataSource(reader))
            val frame = mmr.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: mmr.getFrameAtTime(-1)
            if (frame == null) {
                null
            } else {
                val max = 512f
                val k = minOf(max / frame.width, max / frame.height, 1f)
                if (k < 1f) {
                    Bitmap.createScaledBitmap(
                        frame, (frame.width * k).toInt().coerceAtLeast(1),
                        (frame.height * k).toInt().coerceAtLeast(1), true
                    )
                } else {
                    frame
                }
            }
        } catch (e: Exception) {
            null
        } finally {
            try { mmr.release() } catch (e: Exception) { }
            try { reader.close() } catch (e: Exception) { }
        }
    }
}

/** Чтение расшифрованных данных с произвольного места (нужно для перемотки видео). */
class VaultReader internal constructor(
    private val raf: RandomAccessFile,
    private val header: ByteArray,
    val plainLen: Long,
    private val key: SecretKeySpec
) : Closeable {

    private var cachedIdx = -1L
    private var cached = ByteArray(0)

    @Synchronized
    fun read(pos: Long, dst: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (pos >= plainLen) return -1
        var done = 0
        var p = pos
        while (done < len && p < plainLen) {
            val idx = p / VaultCrypto.CHUNK
            val chunk = loadChunk(idx)
            val inChunk = (p - idx * VaultCrypto.CHUNK).toInt()
            val n = minOf(len - done, chunk.size - inChunk)
            System.arraycopy(chunk, inChunk, dst, off + done, n)
            done += n
            p += n
        }
        return done
    }

    private fun loadChunk(idx: Long): ByteArray {
        if (idx == cachedIdx) return cached
        val start = idx * VaultCrypto.CHUNK
        val plainThis = minOf(VaultCrypto.CHUNK.toLong(), plainLen - start).toInt()
        val enc = ByteArray(plainThis + VaultCrypto.TAG)
        raf.seek(VaultCrypto.HEADER + idx * (VaultCrypto.CHUNK + VaultCrypto.TAG))
        raf.readFully(enc)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, VaultCrypto.nonce(idx)))
        c.updateAAD(header)
        val plain = c.doFinal(enc)
        cached = plain
        cachedIdx = idx
        return plain
    }

    override fun close() {
        try { raf.close() } catch (e: Exception) { }
        cached = ByteArray(0)
        cachedIdx = -1L
    }
}

class VaultInputStream(private val r: VaultReader) : InputStream() {
    private var pos = 0L

    override fun read(): Int {
        val b = ByteArray(1)
        val n = read(b, 0, 1)
        return if (n <= 0) -1 else b[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        val n = r.read(pos, b, off, len)
        if (n > 0) pos += n
        return n
    }

    override fun skip(n: Long): Long {
        val s = minOf(n, r.plainLen - pos).coerceAtLeast(0)
        pos += s
        return s
    }

    override fun available(): Int = minOf(r.plainLen - pos, Int.MAX_VALUE.toLong()).toInt()

    override fun close() = r.close()
}

class VaultMediaDataSource(private val reader: VaultReader) : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int =
        reader.read(position, buffer, offset, size)

    override fun getSize(): Long = reader.plainLen

    override fun close() = reader.close()
}

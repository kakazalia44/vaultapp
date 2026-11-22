package com.example.vault

import android.content.Context
import android.util.Base64
import android.webkit.MimeTypeMap
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class Clip(val files: List<File>, val move: Boolean)

object Fs {

    /** Закрытая папка приложения: другие приложения и галерея её не видят. */
    fun vaultRoot(ctx: Context): File = File(ctx.filesDir, "vault").apply { mkdirs() }

    fun mimeOf(f: File): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase())
            ?: "application/octet-stream"

    fun isVideo(f: File): Boolean = f.isFile && mimeOf(f).startsWith("video/")
    fun isImage(f: File): Boolean = f.isFile && mimeOf(f).startsWith("image/")
    fun isMedia(f: File): Boolean = isVideo(f) || isImage(f)

    /** Папки сверху, потом файлы; по имени или по дате (новые сверху). */
    fun list(dir: File, byDate: Boolean): List<File> {
        val all = dir.listFiles()?.toList() ?: emptyList()
        fun sorted(l: List<File>): List<File> =
            if (byDate) l.sortedByDescending { it.lastModified() }
            else l.sortedBy { it.name.lowercase() }
        return sorted(all.filter { it.isDirectory }) + sorted(all.filter { !it.isDirectory })
    }

    fun formatSize(b: Long): String = when {
        b < 1024L -> "$b Б"
        b < 1024L * 1024 -> "${b / 1024} КБ"
        b < 1024L * 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f МБ", b / 1048576.0)
        else -> String.format(Locale.getDefault(), "%.2f ГБ", b / 1073741824.0)
    }

    fun validName(name: String): Boolean =
        name.isNotBlank() && !name.contains('/') && name != "." && name != ".."

    /** Если такое имя уже занято, добавляет « (1)», « (2)» и т. д. */
    fun uniqueDest(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val isDir = f.isDirectory
        val base = if (isDir) name else f.nameWithoutExtension
        val ext = if (isDir) "" else f.extension
        var i = 1
        while (true) {
            f = File(dir, if (ext.isEmpty()) "$base ($i)" else "$base ($i).$ext")
            if (!f.exists()) return f
            i++
        }
    }

    /** Копирует или перемещает файлы и папки в destDir. Возвращает, сколько элементов обработано. */
    fun paste(items: List<File>, move: Boolean, destDir: File): Int {
        var ok = 0
        for (src in items) {
            var dest: File? = null
            try {
                if (!src.exists()) continue
                // Нельзя вставить папку саму в себя
                if (src.isDirectory &&
                    (destDir.path == src.path || destDir.path.startsWith(src.path + "/"))
                ) continue
                // Перемещение в ту же папку ничего не делает
                if (move && src.parentFile?.path == destDir.path) continue

                dest = uniqueDest(destDir, src.name)
                if (move) {
                    if (src.renameTo(dest)) {
                        ok++
                    } else if (src.copyRecursively(dest, overwrite = false)) {
                        preserveTimes(src, dest)
                        src.deleteRecursively()
                        ok++
                    } else {
                        dest.deleteRecursively()
                    }
                } else {
                    if (src.copyRecursively(dest, overwrite = false)) {
                        preserveTimes(src, dest)
                        ok++
                    } else {
                        dest.deleteRecursively()
                    }
                }
            } catch (e: Exception) {
                // Не удалось — убираем недокопированный остаток, оригинал не трогаем
                dest?.deleteRecursively()
            }
        }
        return ok
    }

    /** Копия файла получает ту же дату изменения, что и оригинал. */
    private fun preserveTimes(src: File, dest: File) {
        try {
            if (src.isDirectory) src.listFiles()?.forEach { preserveTimes(it, File(dest, it.name)) }
            dest.setLastModified(src.lastModified())
        } catch (e: Exception) {
            // дата не важна настолько, чтобы прерывать копирование
        }
    }

    /** Все файлы нужного вида из всех вложенных папок, новые сверху. kind: 1 — фото, 2 — видео, 3 — остальное. */
    fun listAll(root: File, kind: Int): List<File> {
        val out = mutableListOf<File>()
        fun walk(d: File) {
            d.listFiles()?.forEach { f ->
                if (f.isDirectory) {
                    walk(f)
                } else {
                    val match = when (kind) {
                        1 -> isImage(f)
                        2 -> isVideo(f)
                        else -> !isMedia(f)
                    }
                    if (match) out.add(f)
                }
            }
        }
        walk(root)
        return out.sortedByDescending { it.lastModified() }
    }

    /** Разворачивает выбранные файлы и папки в список «относительный путь → файл». */
    fun collect(items: List<File>): List<Pair<String, File>> {
        val out = mutableListOf<Pair<String, File>>()
        fun walk(f: File, rel: String) {
            if (f.isDirectory) {
                f.listFiles()?.sortedBy { it.name }?.forEach { walk(it, rel + "/" + it.name) }
            } else if (f.isFile) {
                out.add(Pair(rel, f))
            }
        }
        for (item in items) walk(item, item.name)
        return out
    }
}

// ───────────────────────── PIN-код ─────────────────────────

class PinStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("vault_prefs", Context.MODE_PRIVATE)

    fun isSet(): Boolean = prefs.contains("hash")

    fun save(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs.edit()
            .putString("salt", enc(salt))
            .putString("hash", enc(hash(pin, salt)))
            .apply()
    }

    fun check(pin: String): Boolean {
        val salt = prefs.getString("salt", null) ?: return false
        val saved = prefs.getString("hash", null) ?: return false
        return MessageDigest.isEqual(hash(pin, dec(salt)), dec(saved))
    }

    private fun hash(pin: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(pin.toCharArray(), salt, 120_000, 256))
            .encoded

    private fun enc(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun dec(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)
}

package com.example.vault

import android.os.Build
import android.os.Environment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.coroutines.coroutineContext

/** Передача файлов между двумя телефонами с этим приложением в одной Wi-Fi сети. */
object Share {
    const val PORT = 45454
    const val DISCOVERY_PORT = 45455
    const val MAGIC = "PRV1"
    const val PROBE = "PRV?"
    const val ANSWER = "PRV!"

    fun saveDir(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Получено")

    private fun broadcasts(): List<InetAddress> {
        val r = mutableListOf<InetAddress>()
        try {
            for (ni in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) {
                    val b = ia.broadcast
                    if (b != null) r.add(b)
                }
            }
        } catch (e: Exception) {
            // нет интерфейсов
        }
        try {
            r.add(InetAddress.getByName("255.255.255.255"))
        } catch (e: Exception) {
            // пропускаем
        }
        return r.distinct()
    }

    /** Ищет в сети телефоны, которые сейчас в режиме приёма. Блокирующий вызов (~2,5 с). */
    fun discover(): List<Peer> {
        val found = LinkedHashMap<String, String>()
        try {
            DatagramSocket().use { s ->
                s.broadcast = true
                s.soTimeout = 400
                val probe = PROBE.toByteArray()
                val targets = broadcasts()
                val end = System.currentTimeMillis() + 2500
                var lastSend = 0L
                val buf = ByteArray(512)
                while (System.currentTimeMillis() < end) {
                    if (System.currentTimeMillis() - lastSend > 800) {
                        for (t in targets) {
                            try {
                                s.send(DatagramPacket(probe, probe.size, t, DISCOVERY_PORT))
                            } catch (e: Exception) {
                                // адрес недоступен
                            }
                        }
                        lastSend = System.currentTimeMillis()
                    }
                    try {
                        val p = DatagramPacket(buf, buf.size)
                        s.receive(p)
                        val msg = String(p.data, 0, p.length, Charsets.UTF_8)
                        if (msg.startsWith(ANSWER)) {
                            val ip = p.address.hostAddress
                            if (ip != null) found[ip] = msg.removePrefix(ANSWER)
                        }
                    } catch (e: SocketTimeoutException) {
                        // ждём дальше
                    }
                }
            }
        } catch (e: Exception) {
            // поиск не удался — остаётся ручной ввод адреса
        }
        return found.map { Peer(it.key, it.value) }
    }

    /** Отправляет файлы на телефон с адресом host. Бросает исключение при ошибке. */
    suspend fun send(
        host: String,
        code: String,
        items: List<Pair<String, File>>,
        onProgress: (String) -> Unit
    ) {
        withContext(Dispatchers.IO) {
            val total = items.sumOf { Fs.sizeOf(it.second) }.coerceAtLeast(1L)
            var sent = 0L
            Socket().use { sock ->
                sock.connect(InetSocketAddress(host, PORT), 5000)
                sock.soTimeout = 30_000
                val out = DataOutputStream(BufferedOutputStream(sock.getOutputStream(), 65536))
                val inp = DataInputStream(sock.getInputStream())

                out.writeUTF(MAGIC)
                out.writeUTF(code)
                out.writeInt(items.size)
                out.flush()
                if (inp.readByte().toInt() != 1) throw IOException("Неверный код")

                val buf = ByteArray(65536)
                var lastPct = -1
                for ((index, item) in items.withIndex()) {
                    val rel = item.first
                    val f = item.second
                    val len = Fs.sizeOf(f)
                    out.writeUTF(rel)
                    out.writeLong(len)
                    var left = len
                    Fs.openInput(f).use { ins ->
                        while (left > 0) {
                            coroutineContext.ensureActive()
                            val n = ins.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                            if (n < 0) throw EOFException("Файл изменился во время отправки")
                            out.write(buf, 0, n)
                            left -= n
                            sent += n
                            val pct = (sent * 100 / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress("Отправка ${index + 1} из ${items.size}: $pct%")
                            }
                        }
                    }
                }
                out.flush()
                inp.readByte() // подтверждение от получателя
            }
        }
    }
}

class Peer(val ip: String, val name: String)

/** Принимающая сторона: показывает код, ждёт отправителя и сохраняет файлы в Загрузки/Получено. */
class ReceiveServer {
    val code: String = String.format(Locale.US, "%06d", SecureRandom().nextInt(1_000_000))
    var status by mutableStateOf("Ожидание отправителя…")
    var progress by mutableStateOf("")
    val received = mutableStateListOf<String>()

    private var tcp: ServerSocket? = null
    private var udp: DatagramSocket? = null
    private var failures = 0

    @Volatile
    private var stopped = false

    fun start() {
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(Share.PORT))
        tcp = s
        val u = DatagramSocket(null)
        u.reuseAddress = true
        u.bind(InetSocketAddress(Share.DISCOVERY_PORT))
        u.broadcast = true
        udp = u

        thread(isDaemon = true) {
            while (!stopped) {
                try {
                    val c = s.accept()
                    handle(c)
                } catch (e: Exception) {
                    if (stopped) break
                }
            }
        }
        thread(isDaemon = true) {
            val buf = ByteArray(512)
            val answer = (Share.ANSWER + Build.MODEL).toByteArray(Charsets.UTF_8)
            while (!stopped) {
                try {
                    val p = DatagramPacket(buf, buf.size)
                    u.receive(p)
                    val msg = String(p.data, 0, p.length, Charsets.UTF_8)
                    if (msg == Share.PROBE) {
                        u.send(DatagramPacket(answer, answer.size, p.address, p.port))
                    }
                } catch (e: Exception) {
                    if (stopped) break
                }
            }
        }
    }

    fun stop() {
        stopped = true
        try { tcp?.close() } catch (e: Exception) { }
        try { udp?.close() } catch (e: Exception) { }
    }

    private fun cleanPath(rel: String): List<String> =
        rel.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." && it != ".." }

    private fun handle(c: Socket) {
        try {
            c.use { sock ->
                sock.soTimeout = 30_000
                val inp = DataInputStream(BufferedInputStream(sock.getInputStream(), 65536))
                val out = DataOutputStream(sock.getOutputStream())

                if (inp.readUTF() != Share.MAGIC) return
                val theirs = inp.readUTF()
                if (failures >= 5 || !MessageDigest.isEqual(theirs.toByteArray(), code.toByteArray())) {
                    failures++
                    out.writeByte(0)
                    out.flush()
                    status = "Отправитель ввёл неверный код ($failures)"
                    if (failures >= 5) {
                        status = "Слишком много неверных кодов. Приём остановлен"
                        stop()
                    }
                    return
                }
                out.writeByte(1)
                out.flush()

                val count = inp.readInt()
                if (count < 0 || count > 100_000) return
                status = "Идёт приём…"
                val base = Share.saveDir()
                val buf = ByteArray(65536)
                var done = 0
                for (i in 0 until count) {
                    val parts = cleanPath(inp.readUTF())
                    val size = inp.readLong()
                    if (parts.isEmpty() || size < 0) throw IOException("Некорректные данные")
                    var parent = base
                    for (d in parts.dropLast(1)) parent = File(parent, d)
                    parent.mkdirs()
                    val dest = Fs.uniqueDest(parent, parts.last())
                    var left = size
                    var lastPct = -1
                    try {
                        FileOutputStream(dest).use { fo ->
                            while (left > 0) {
                                val n = inp.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                                if (n < 0) throw EOFException()
                                fo.write(buf, 0, n)
                                left -= n
                                val pct = if (size == 0L) 100 else ((size - left) * 100 / size).toInt()
                                if (pct != lastPct) {
                                    lastPct = pct
                                    progress = "${parts.last()}: $pct%"
                                }
                            }
                        }
                    } catch (e: Exception) {
                        dest.delete()
                        throw e
                    }
                    done++
                    received.add(parts.joinToString("/") + " · " + Fs.formatSize(size))
                }
                out.writeByte(1)
                out.flush()
                progress = ""
                status = "Получено файлов: $done. Ожидание следующей отправки…"
            }
        } catch (e: Exception) {
            if (!stopped) {
                progress = ""
                status = "Передача прервалась: " + (e.message ?: e.javaClass.simpleName)
            }
        }
    }
}

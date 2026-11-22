package com.example.vault

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.wifi.WifiManager
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

// ───────────────────────── Состояние FTP для экрана ─────────────────────────

object FtpState {
    const val PORT = 2121
    const val USER = "user"

    var running by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var password by mutableStateOf("")

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences("ftp_prefs", Context.MODE_PRIVATE)
        val saved = p.getString("pw", null)
        if (saved != null) {
            password = saved
        } else {
            val generated = String.format(Locale.US, "%06d", SecureRandom().nextInt(1_000_000))
            p.edit().putString("pw", generated).apply()
            password = generated
        }
    }

    fun savePassword(ctx: Context, pw: String) {
        password = pw
        ctx.getSharedPreferences("ftp_prefs", Context.MODE_PRIVATE).edit().putString("pw", pw).apply()
    }
}

/** IPv4-адреса телефона в локальной сети (Wi-Fi, точка доступа, Ethernet). */
fun localIps(): List<String> {
    val res = mutableListOf<String>()
    try {
        for (ni in Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!ni.isUp || ni.isLoopback) continue
            val n = ni.name.lowercase()
            if (n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("tun") ||
                n.startsWith("v4-") || n.startsWith("dummy")
            ) continue
            for (a in Collections.list(ni.inetAddresses)) {
                if (a is Inet4Address && !a.isLoopbackAddress && a.isSiteLocalAddress) {
                    val h = a.hostAddress
                    if (h != null) res.add(h)
                }
            }
        }
    } catch (e: Exception) {
        // нет сетевых интерфейсов
    }
    return res.distinct()
}

// ───────────────────────── Служба, которая держит FTP включённым ─────────────────────────

class FtpService : Service() {

    companion object {
        const val ACTION_STOP = "com.example.vault.FTP_STOP"
        private const val CHANNEL = "ftp"
        private const val NOTIF_ID = 21

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, FtpService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, FtpService::class.java))
        }
    }

    private var server: FtpServerImpl? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        showForeground()
        if (server == null) {
            try {
                val s = FtpServerImpl(
                    Environment.getExternalStorageDirectory(),
                    { FtpState.password },
                    FtpState.PORT
                )
                s.start()
                server = s
                acquireLocks()
                FtpState.error = null
                FtpState.running = true
            } catch (e: Exception) {
                FtpState.error = "Не удалось запустить: " + (e.message ?: e.javaClass.simpleName)
                FtpState.running = false
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        try { wake?.release() } catch (e: Exception) { }
        try { wifi?.release() } catch (e: Exception) { }
        FtpState.running = false
        super.onDestroy()
    }

    private fun acquireLocks() {
        try {
            val pm = getSystemService(PowerManager::class.java)
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "provodnik:ftp").apply { acquire() }
            val wm = applicationContext.getSystemService(WifiManager::class.java)
            wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "provodnik:ftp")
                .apply { acquire() }
        } catch (e: Exception) {
            // без блокировок сервер тоже работает, просто может засыпать вместе с экраном
        }
    }

    private fun showForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "FTP", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, FtpService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val action = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
            "Выключить",
            stop
        ).build()
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Доступ по FTP включён")
            .setContentText("Порт ${FtpState.PORT}")
            .setContentIntent(open)
            .addAction(action)
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
}

// ───────────────────────── Сам FTP-сервер ─────────────────────────

class FtpServerImpl(
    private val root: File,
    private val getPassword: () -> String,
    private val port: Int
) {
    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    private val sessions = ConcurrentHashMap.newKeySet<Socket>()

    @Volatile
    private var running = false

    fun start() {
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(port))
        server = s
        running = true
        pool.execute {
            while (running) {
                try {
                    val c = s.accept()
                    pool.execute { Session(c).serve() }
                } catch (e: Exception) {
                    if (!running) break
                }
            }
        }
    }

    fun stop() {
        running = false
        try { server?.close() } catch (e: Exception) { }
        for (c in sessions) {
            try { c.close() } catch (e: Exception) { }
        }
        pool.shutdownNow()
    }

    private inner class Session(private val ctl: Socket) {
        private var userOk = false
        private var loggedIn = false
        private var fails = 0
        private var cwd = "/"
        private var pasv: ServerSocket? = null
        private var activeAddr: InetSocketAddress? = null
        private var rest = 0L
        private var renameFrom: File? = null
        private lateinit var out: BufferedWriter

        fun serve() {
            sessions.add(ctl)
            try {
                ctl.soTimeout = 10 * 60 * 1000
                val inp = BufferedReader(InputStreamReader(ctl.getInputStream(), Charsets.UTF_8))
                out = BufferedWriter(OutputStreamWriter(ctl.getOutputStream(), Charsets.UTF_8))
                reply("220 Ready")
                while (true) {
                    val line = inp.readLine() ?: break
                    if (!handle(line.trim())) break
                }
            } catch (e: Exception) {
                // соединение закрыто
            } finally {
                closePasv()
                sessions.remove(ctl)
                try { ctl.close() } catch (e: Exception) { }
            }
        }

        private fun reply(s: String) {
            out.write(s)
            out.write("\r\n")
            out.flush()
        }

        private fun closePasv() {
            try { pasv?.close() } catch (e: Exception) { }
            pasv = null
        }

        /** Приводит путь от клиента к виду «/папка/файл» без «..». */
        private fun virtual(arg: String): String {
            val base = if (arg.startsWith("/")) arg else "$cwd/$arg"
            val parts = mutableListOf<String>()
            for (p in base.split('/')) {
                when (p) {
                    "", "." -> {}
                    ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                    else -> parts.add(p)
                }
            }
            return "/" + parts.joinToString("/")
        }

        private fun file(v: String): File = if (v == "/") root else File(root, v.substring(1))

        private fun openData(): Socket? {
            return try {
                val p = pasv
                if (p != null) {
                    p.soTimeout = 30_000
                    val s = p.accept()
                    closePasv()
                    s
                } else {
                    val a = activeAddr ?: return null
                    val s = Socket()
                    s.connect(a, 15_000)
                    activeAddr = null
                    s
                }
            } catch (e: Exception) {
                closePasv()
                null
            }
        }

        private fun withData(block: (Socket) -> Unit) {
            reply("150 Opening data connection")
            val d = openData()
            if (d == null) {
                reply("425 Can't open data connection")
                return
            }
            var ok = false
            try {
                d.soTimeout = 60_000
                block(d)
                ok = true
            } catch (e: Exception) {
                // обрыв передачи
            } finally {
                try { d.close() } catch (e: Exception) { }
            }
            reply(if (ok) "226 Transfer complete" else "426 Transfer aborted")
        }

        private fun listLine(f: File): String {
            val now = System.currentTimeMillis()
            val recent = f.lastModified() > now - 180L * 24 * 3600 * 1000 && f.lastModified() <= now + 24 * 3600 * 1000
            val fmt = SimpleDateFormat(if (recent) "MMM dd HH:mm" else "MMM dd  yyyy", Locale.US)
            val type = if (f.isDirectory) "drwxrwxrwx" else "-rw-rw-rw-"
            val size = if (f.isDirectory) 4096L else f.length()
            return "$type 1 user group $size ${fmt.format(Date(f.lastModified()))} ${f.name}"
        }

        private fun handle(line: String): Boolean {
            if (line.isEmpty()) return true
            val sp = line.indexOf(' ')
            val cmd = (if (sp < 0) line else line.substring(0, sp)).uppercase(Locale.US)
            val arg = if (sp < 0) "" else line.substring(sp + 1).trim()

            if (!loggedIn && cmd !in setOf("USER", "PASS", "QUIT", "FEAT", "OPTS", "SYST", "NOOP")) {
                reply("530 Please login with USER and PASS")
                return true
            }
            return try {
                dispatch(cmd, arg)
            } catch (e: IOException) {
                throw e
            } catch (e: Exception) {
                reply("501 Syntax error")
                true
            }
        }

        private fun dispatch(cmd: String, arg: String): Boolean {
            when (cmd) {
                "USER" -> {
                    userOk = arg == FtpState.USER
                    reply("331 Password required")
                }
                "PASS" -> {
                    val pw = getPassword()
                    val ok = userOk && pw.isNotEmpty() &&
                        MessageDigest.isEqual(arg.toByteArray(), pw.toByteArray())
                    userOk = false
                    if (ok) {
                        loggedIn = true
                        reply("230 Logged in")
                    } else {
                        Thread.sleep(1000)
                        fails++
                        reply("530 Login incorrect")
                        if (fails >= 5) return false
                    }
                }
                "QUIT" -> {
                    reply("221 Bye")
                    return false
                }
                "SYST" -> reply("215 UNIX Type: L8")
                "FEAT" -> {
                    out.write("211-Features:\r\n UTF8\r\n SIZE\r\n MDTM\r\n REST STREAM\r\n EPSV\r\n211 End\r\n")
                    out.flush()
                }
                "OPTS" -> reply("200 OK")
                "NOOP" -> reply("200 OK")
                "TYPE", "MODE", "STRU" -> reply("200 OK")
                "ABOR" -> reply("226 OK")
                "PWD", "XPWD" -> reply("257 \"" + cwd.replace("\"", "\"\"") + "\" is current directory")
                "CWD", "XCWD" -> {
                    val v = virtual(arg)
                    if (file(v).isDirectory) {
                        cwd = v
                        reply("250 OK")
                    } else {
                        reply("550 No such directory")
                    }
                }
                "CDUP", "XCUP" -> {
                    cwd = virtual("..")
                    reply("250 OK")
                }
                "PASV" -> {
                    closePasv()
                    val s = ServerSocket(0, 1, ctl.localAddress)
                    pasv = s
                    var ip = ctl.localAddress.address
                    if (ip.size == 16) ip = ip.copyOfRange(12, 16)
                    val p = s.localPort
                    reply(
                        "227 Entering Passive Mode (" +
                            ip.joinToString(",") { (it.toInt() and 255).toString() } +
                            ",${p shr 8},${p and 255})"
                    )
                }
                "EPSV" -> {
                    closePasv()
                    val s = ServerSocket(0, 1, ctl.localAddress)
                    pasv = s
                    reply("229 Entering Extended Passive Mode (|||${s.localPort}|)")
                }
                "PORT" -> {
                    val p = arg.split(',').map { it.trim().toInt() }
                    if (p.size != 6) {
                        reply("501 Bad PORT")
                    } else {
                        val ip = p.take(4).joinToString(".")
                        if (ip != ctl.inetAddress.hostAddress) {
                            reply("504 Only your own address is allowed")
                        } else {
                            closePasv()
                            activeAddr = InetSocketAddress(ip, p[4] * 256 + p[5])
                            reply("200 OK")
                        }
                    }
                }
                "REST" -> {
                    rest = arg.toLong()
                    reply("350 Restarting")
                }
                "LIST", "NLST" -> {
                    val a = if (arg.startsWith("-")) arg.substringAfter(' ', "") else arg
                    val f = file(virtual(a))
                    if (!f.exists()) {
                        reply("550 Not found")
                    } else {
                        val entries = if (f.isDirectory) (f.listFiles()?.toList() ?: emptyList()) else listOf(f)
                        withData { d ->
                            val w = d.getOutputStream().bufferedWriter(Charsets.UTF_8)
                            for (e in entries.sortedBy { it.name.lowercase() }) {
                                w.write(if (cmd == "NLST") e.name else listLine(e))
                                w.write("\r\n")
                            }
                            w.flush()
                        }
                    }
                }
                "RETR" -> {
                    val f = file(virtual(arg))
                    if (!f.isFile) {
                        reply("550 Not a file")
                    } else {
                        val start = rest
                        rest = 0
                        withData { d ->
                            FileInputStream(f).use { ins ->
                                if (start > 0) ins.channel.position(start)
                                val o = d.getOutputStream()
                                ins.copyTo(o, 65536)
                                o.flush()
                            }
                        }
                    }
                }
                "STOR", "APPE" -> {
                    val f = file(virtual(arg))
                    rest = 0
                    if (f.isDirectory || f.parentFile?.isDirectory != true) {
                        reply("550 Can't write there")
                    } else {
                        withData { d ->
                            FileOutputStream(f, cmd == "APPE").use { o ->
                                d.getInputStream().copyTo(o, 65536)
                            }
                        }
                    }
                }
                "DELE" -> {
                    val f = file(virtual(arg))
                    if (f.isFile && f.delete()) reply("250 Deleted") else reply("550 Can't delete")
                }
                "RMD", "XRMD" -> {
                    val f = file(virtual(arg))
                    if (f.isDirectory && f != root && f.delete()) reply("250 Removed") else reply("550 Can't remove")
                }
                "MKD", "XMKD" -> {
                    val v = virtual(arg)
                    if (file(v).mkdir()) {
                        reply("257 \"" + v.replace("\"", "\"\"") + "\" created")
                    } else {
                        reply("550 Can't create")
                    }
                }
                "RNFR" -> {
                    val f = file(virtual(arg))
                    if (f.exists() && f != root) {
                        renameFrom = f
                        reply("350 Ready for RNTO")
                    } else {
                        reply("550 Not found")
                    }
                }
                "RNTO" -> {
                    val from = renameFrom
                    renameFrom = null
                    val to = file(virtual(arg))
                    if (from != null && !to.exists() && from.renameTo(to)) {
                        reply("250 Renamed")
                    } else {
                        reply("550 Can't rename")
                    }
                }
                "SIZE" -> {
                    val f = file(virtual(arg))
                    if (f.isFile) reply("213 ${f.length()}") else reply("550 Not a file")
                }
                "MDTM" -> {
                    val f = file(virtual(arg))
                    if (f.exists()) {
                        val fmt = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
                        fmt.timeZone = TimeZone.getTimeZone("UTC")
                        reply("213 " + fmt.format(Date(f.lastModified())))
                    } else {
                        reply("550 Not found")
                    }
                }
                else -> reply("502 Command not implemented")
            }
            return true
        }
    }
}

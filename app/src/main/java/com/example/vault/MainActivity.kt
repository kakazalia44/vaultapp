package com.example.vault

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Base64
import android.view.WindowManager
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

// ───────────────────────── Activity ─────────────────────────

class MainActivity : ComponentActivity() {

    private var unlocked by mutableStateOf(false)
    private var storageGranted by mutableStateOf(false)
    private var externalLaunch = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Запрет скриншотов и скрытие превью в списке последних приложений
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    VaultApp(
                        unlocked = unlocked,
                        storageGranted = storageGranted,
                        onUnlock = { unlocked = true },
                        onRequestStorage = { openStorageSettings() }
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        externalLaunch = false
    }

    override fun onResume() {
        super.onResume()
        storageGranted = Environment.isExternalStorageManager()
    }

    // Свернули приложение — всё закрывается за PIN-кодом
    override fun onStop() {
        super.onStop()
        if (!externalLaunch && !isChangingConfigurations) {
            unlocked = false
        }
    }

    private fun openStorageSettings() {
        externalLaunch = true
        try {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }
}

// ───────────────────────── Работа с файлами ─────────────────────────

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
                        src.deleteRecursively()
                        ok++
                    } else {
                        dest.deleteRecursively()
                    }
                } else {
                    if (src.copyRecursively(dest, overwrite = false)) ok++ else dest.deleteRecursively()
                }
            } catch (e: Exception) {
                // Не удалось — убираем недокопированный остаток, оригинал не трогаем
                dest?.deleteRecursively()
            }
        }
        return ok
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

// ───────────────────────── Вход ─────────────────────────

@Composable
fun VaultApp(
    unlocked: Boolean,
    storageGranted: Boolean,
    onUnlock: () -> Unit,
    onRequestStorage: () -> Unit
) {
    val ctx = LocalContext.current
    val pins = remember { PinStore(ctx) }
    var hasPin by remember { mutableStateOf(pins.isSet()) }

    when {
        !hasPin -> SetupPinScreen(onDone = { pin ->
            pins.save(pin)
            hasPin = true
            onUnlock()
        })
        !unlocked -> LoginScreen(check = { pins.check(it) }, onSuccess = onUnlock)
        else -> ExplorerScreen(storageGranted, onRequestStorage)
    }
}

@Composable
fun PinEntry(
    title: String,
    hint: String?,
    error: String?,
    button: String,
    enabled: Boolean = true,
    onSubmit: (String) -> Unit
) {
    var pin by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        if (hint != null) {
            Spacer(Modifier.height(8.dp))
            Text(hint, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = pin,
            onValueChange = { v -> if (v.length <= 8 && v.all { it.isDigit() }) pin = v },
            singleLine = true,
            label = { Text("PIN") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
        )
        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(error, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                val value = pin
                pin = ""
                onSubmit(value)
            },
            enabled = enabled && pin.length >= 4
        ) { Text(button) }
    }
}

@Composable
fun SetupPinScreen(onDone: (String) -> Unit) {
    var first by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    PinEntry(
        title = if (first == null) "Придумайте PIN-код" else "Повторите PIN-код",
        hint = if (first == null) "От 4 до 8 цифр. Восстановить его нельзя — не забудьте." else null,
        error = error,
        button = if (first == null) "Далее" else "Готово"
    ) { pin ->
        val f = first
        when {
            f == null -> {
                first = pin
                error = null
            }
            f == pin -> onDone(pin)
            else -> {
                first = null
                error = "PIN-коды не совпали, начните заново"
            }
        }
    }
}

@Composable
fun LoginScreen(check: (String) -> Boolean, onSuccess: () -> Unit) {
    var fails by remember { mutableIntStateOf(0) }
    var lockLeft by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }

    // Обратный отсчёт блокировки после 5 неверных попыток
    LaunchedEffect(lockLeft) {
        if (lockLeft > 0) {
            delay(1000)
            lockLeft -= 1
        }
    }

    PinEntry(
        title = "Сейф",
        hint = null,
        error = if (lockLeft > 0) "Слишком много попыток. Подождите $lockLeft с" else error,
        button = "Войти",
        enabled = lockLeft == 0
    ) { pin ->
        if (check(pin)) {
            onSuccess()
        } else {
            fails += 1
            error = "Неверный PIN"
            if (fails % 5 == 0) lockLeft = 30
        }
    }
}

// ───────────────────────── Проводник ─────────────────────────

class Clip(val files: List<File>, val move: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExplorerScreen(storageGranted: Boolean, onRequestStorage: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val phoneRoot = remember { Environment.getExternalStorageDirectory() }
    val vaultRoot = remember { Fs.vaultRoot(ctx) }

    var tab by remember { mutableIntStateOf(0) } // 0 — телефон, 1 — сейф
    var phoneDir by remember { mutableStateOf(phoneRoot) }
    var vaultDir by remember { mutableStateOf(vaultRoot) }
    var clip by remember { mutableStateOf<Clip?>(null) }
    var selected by remember { mutableStateOf(setOf<File>()) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var byDate by remember { mutableStateOf(false) }
    var viewer by remember { mutableStateOf<Pair<List<File>, Int>?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var showNewFolder by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<File?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val loader = remember {
        ImageLoader.Builder(ctx)
            .components { add(VideoFrameDecoder.Factory()) }
            .build()
    }
    val dateFmt = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }

    val root = if (tab == 0) phoneRoot else vaultRoot
    val dir = if (tab == 0) phoneDir else vaultDir
    val showList = tab == 1 || storageGranted

    fun setDir(f: File) {
        if (tab == 0) phoneDir = f else vaultDir = f
        selected = emptySet()
    }

    fun toast(text: String) {
        Toast.makeText(ctx, text, Toast.LENGTH_LONG).show()
    }

    val entries = remember(dir, refreshKey, byDate, showList) {
        if (showList) Fs.list(dir, byDate) else emptyList()
    }

    // При входе в другую папку или вкладку — наверх списка
    LaunchedEffect(dir, tab) { listState.scrollToItem(0) }

    BackHandler(enabled = selected.isNotEmpty() || (showList && dir != root)) {
        if (selected.isNotEmpty()) {
            selected = emptySet()
        } else {
            dir.parentFile?.let { setDir(it) }
        }
    }

    val v = viewer
    if (v != null) {
        BackHandler { viewer = null }
        Viewer(files = v.first, start = v.second, loader = loader, onClose = { viewer = null })
    } else {
        Scaffold(
            topBar = {
                Column {
                    TopAppBar(
                        title = {
                            Text(
                                when {
                                    selected.isNotEmpty() -> "Выбрано: ${selected.size}"
                                    dir == root -> "Проводник"
                                    else -> dir.name
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        actions = {
                            if (showList) {
                                Box {
                                    TextButton(onClick = { menuOpen = true }) { Text("⋮", fontSize = 22.sp) }
                                    DropdownMenu(
                                        expanded = menuOpen,
                                        onDismissRequest = { menuOpen = false }
                                    ) {
                                        if (selected.isEmpty()) {
                                            DropdownMenuItem(
                                                text = { Text("Новая папка") },
                                                onClick = { menuOpen = false; showNewFolder = true }
                                            )
                                            DropdownMenuItem(
                                                text = {
                                                    Text(if (byDate) "Сортировать по имени" else "Сортировать по дате")
                                                },
                                                onClick = { menuOpen = false; byDate = !byDate }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Обновить") },
                                                onClick = { menuOpen = false; refreshKey++ }
                                            )
                                        } else {
                                            DropdownMenuItem(
                                                text = { Text("Копировать") },
                                                onClick = {
                                                    menuOpen = false
                                                    clip = Clip(selected.toList(), false)
                                                    selected = emptySet()
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Переместить") },
                                                onClick = {
                                                    menuOpen = false
                                                    clip = Clip(selected.toList(), true)
                                                    selected = emptySet()
                                                }
                                            )
                                            if (selected.size == 1) {
                                                DropdownMenuItem(
                                                    text = { Text("Переименовать") },
                                                    onClick = {
                                                        menuOpen = false
                                                        renameTarget = selected.first()
                                                    }
                                                )
                                            }
                                            DropdownMenuItem(
                                                text = { Text("Удалить") },
                                                onClick = { menuOpen = false; confirmDelete = true }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Выбрать всё") },
                                                onClick = { menuOpen = false; selected = entries.toSet() }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Снять выбор") },
                                                onClick = { menuOpen = false; selected = emptySet() }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    )
                    TabRow(selectedTabIndex = tab) {
                        Tab(
                            selected = tab == 0,
                            onClick = { tab = 0; selected = emptySet() },
                            text = { Text("Телефон") }
                        )
                        Tab(
                            selected = tab == 1,
                            onClick = { tab = 1; selected = emptySet() },
                            text = { Text("Сейф") }
                        )
                    }
                }
            },
            bottomBar = {
                val c = clip
                if (c != null && showList) {
                    Surface(tonalElevation = 3.dp) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .navigationBarsPadding()
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                (if (c.move) "Перемещение: " else "Копирование: ") + c.files.size,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = {
                                val target = dir
                                busy = true
                                scope.launch {
                                    val n = withContext(Dispatchers.IO) {
                                        Fs.paste(c.files, c.move, target)
                                    }
                                    busy = false
                                    refreshKey++
                                    if (c.move) clip = null
                                    toast("Готово: $n из ${c.files.size}")
                                }
                            }) { Text("Вставить сюда") }
                            TextButton(onClick = { clip = null }) { Text("✕") }
                        }
                    }
                }
            }
        ) { padding ->
            Column(
                Modifier
                    .padding(padding)
                    .fillMaxSize()
            ) {
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())

                if (!showList) {
                    PermissionPrompt(onRequestStorage)
                } else {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (dir != root) {
                            TextButton(onClick = { dir.parentFile?.let { setDir(it) } }) { Text("⬆") }
                        }
                        Text(
                            (if (tab == 0) "Телефон" else "Сейф") + dir.path.removePrefix(root.path),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp)
                        )
                    }

                    if (entries.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Папка пуста")
                        }
                    } else {
                        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                            items(entries, key = { it.path }) { f ->
                                FileRow(
                                    f = f,
                                    selected = f in selected,
                                    stamp = refreshKey,
                                    loader = loader,
                                    dateFmt = dateFmt,
                                    onClick = {
                                        if (selected.isNotEmpty()) {
                                            selected = if (f in selected) selected - f else selected + f
                                        } else if (f.isDirectory) {
                                            setDir(f)
                                        } else if (Fs.isMedia(f)) {
                                            val media = entries.filter { Fs.isMedia(it) }
                                            viewer = Pair(media, media.indexOf(f))
                                        } else {
                                            toast("Этот тип файлов пока нельзя открыть")
                                        }
                                    },
                                    onLongClick = { selected = selected + f }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showNewFolder) {
        NameDialog(
            title = "Новая папка",
            initial = "",
            onDismiss = { showNewFolder = false }
        ) { name ->
            showNewFolder = false
            val d = File(dir, name)
            when {
                !Fs.validName(name) -> toast("Некорректное имя")
                d.exists() -> toast("Такое имя уже есть")
                d.mkdir() -> refreshKey++
                else -> toast("Не удалось создать папку")
            }
        }
    }

    val rt = renameTarget
    if (rt != null) {
        NameDialog(
            title = "Переименовать",
            initial = rt.name,
            onDismiss = { renameTarget = null }
        ) { name ->
            renameTarget = null
            val target = File(rt.parentFile, name)
            when {
                !Fs.validName(name) -> toast("Некорректное имя")
                target.exists() -> toast("Такое имя уже есть")
                rt.renameTo(target) -> {
                    selected = emptySet()
                    refreshKey++
                }
                else -> toast("Не удалось переименовать")
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить элементов: ${selected.size}?") },
            text = { Text("Удаление безвозвратное, корзины нет.") },
            confirmButton = {
                TextButton(onClick = {
                    val items = selected.toList()
                    confirmDelete = false
                    busy = true
                    scope.launch {
                        withContext(Dispatchers.IO) { items.forEach { it.deleteRecursively() } }
                        busy = false
                        selected = emptySet()
                        refreshKey++
                    }
                }) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Отмена") }
            }
        )
    }
}

@Composable
fun PermissionPrompt(onRequest: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "Нужен доступ ко всем файлам",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Чтобы показывать память телефона, Android требует отдельное разрешение. " +
                "В настройках включите переключатель для этого приложения и вернитесь назад.",
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onRequest) { Text("Открыть настройки") }
    }
}

@Composable
fun NameDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onOk: (String) -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true)
        },
        confirmButton = { TextButton(onClick = { onOk(text.trim()) }) { Text("ОК") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileRow(
    f: File,
    selected: Boolean,
    stamp: Int,
    loader: ImageLoader,
    dateFmt: SimpleDateFormat,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val info = remember(f.path, stamp) {
        if (f.isDirectory) {
            "Папка · ${f.list()?.size ?: 0}"
        } else {
            "${Fs.formatSize(f.length())} · ${dateFmt.format(Date(f.lastModified()))}"
        }
    }

    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) Color(0x556650A4) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            when {
                f.isDirectory -> Text("📁", fontSize = 28.sp)
                Fs.isMedia(f) -> {
                    AsyncImage(
                        model = f,
                        imageLoader = loader,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    if (Fs.isVideo(f)) Text("▶", color = Color.White)
                }
                else -> Text("📄", fontSize = 28.sp)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                info,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (selected) Text("✓", style = MaterialTheme.typography.titleLarge)
    }
}

// ───────────────────────── Просмотр фото и видео ─────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Viewer(files: List<File>, start: Int, loader: ImageLoader, onClose: () -> Unit) {
    val pagerState = rememberPagerState(initialPage = start.coerceIn(0, files.lastIndex)) {
        files.size
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .systemBarsPadding()
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onClose) { Text("← Назад") }
            Spacer(Modifier.weight(1f))
            Text(
                "${pagerState.currentPage + 1} / ${files.size}",
                color = Color.White,
                modifier = Modifier.padding(end = 16.dp)
            )
        }
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) { page ->
            val f = files[page]
            if (Fs.isVideo(f)) {
                VideoPage(file = f, isCurrent = pagerState.currentPage == page)
            } else {
                AsyncImage(
                    model = f,
                    imageLoader = loader,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@Composable
fun VideoPage(file: File, isCurrent: Boolean) {
    val ctx = LocalContext.current
    val exo = remember(file) {
        ExoPlayer.Builder(ctx).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            prepare()
        }
    }

    LaunchedEffect(isCurrent) {
        if (isCurrent) exo.play() else exo.pause()
    }

    DisposableEffect(exo) {
        onDispose { exo.release() }
    }

    AndroidView(
        factory = { context ->
            PlayerView(context).apply {
                player = exo
                useController = true
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}

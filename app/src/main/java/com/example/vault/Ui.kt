package com.example.vault

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun Context.findActivity(): Activity? {
    var c: Context = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

@Composable
fun KeepScreenOn() {
    val window = LocalContext.current.findActivity()?.window
    DisposableEffect(Unit) {
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}

// ───────────────────────── PIN скрытого раздела ─────────────────────────

@Composable
fun PinEntry(
    title: String,
    hint: String?,
    error: String?,
    button: String,
    enabled: Boolean = true,
    onCancel: () -> Unit,
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
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
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
        TextButton(onClick = onCancel) { Text("Отмена") }
    }
}

@Composable
fun VaultGate(onSuccess: () -> Unit, onCancel: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val pins = remember { PinStore(ctx) }
    var hasPin by remember { mutableStateOf(pins.isSet()) }
    var working by remember { mutableStateOf(false) }

    BackHandler(onBack = onCancel)

    if (!hasPin) {
        var first by remember { mutableStateOf<String?>(null) }
        var error by remember { mutableStateOf<String?>(null) }
        PinEntry(
            title = if (first == null) "Придумайте PIN скрытого раздела" else "Повторите PIN",
            hint = if (first == null) "От 4 до 8 цифр. Восстановить его нельзя — не забудьте." else null,
            error = error,
            button = if (first == null) "Далее" else "Готово",
            enabled = !working,
            onCancel = onCancel
        ) { pin ->
            val f = first
            when {
                f == null -> {
                    first = pin
                    error = null
                }
                f == pin -> {
                    working = true
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            pins.save(pin)
                            VaultCrypto.unlock(ctx, pin)
                        }
                        working = false
                        if (ok) {
                            hasPin = true
                            onSuccess()
                        } else {
                            first = null
                            error = "Не удалось создать ключ шифрования"
                        }
                    }
                }
                else -> {
                    first = null
                    error = "PIN-коды не совпали, начните заново"
                }
            }
        }
    } else {
        var fails by remember { mutableIntStateOf(0) }
        var lockLeft by remember { mutableIntStateOf(0) }
        var error by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(lockLeft) {
            if (lockLeft > 0) {
                delay(1000)
                lockLeft -= 1
            }
        }

        PinEntry(
            title = "Введите PIN",
            hint = null,
            error = if (lockLeft > 0) "Слишком много попыток. Подождите $lockLeft с" else error,
            button = "Войти",
            enabled = lockLeft == 0 && !working,
            onCancel = onCancel
        ) { pin ->
            working = true
            scope.launch {
                val pinOk = withContext(Dispatchers.IO) { pins.check(pin) }
                if (pinOk) {
                    val keyOk = withContext(Dispatchers.IO) { VaultCrypto.unlock(ctx, pin) }
                    working = false
                    if (keyOk) {
                        onSuccess()
                    } else {
                        error = "Не удалось открыть ключ шифрования"
                    }
                } else {
                    working = false
                    fails += 1
                    error = "Неверный PIN"
                    if (fails % 5 == 0) lockLeft = 30
                }
            }
        }
    }
}

// ───────────────────────── Проводник ─────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ExplorerScreen(
    inVault: Boolean,
    setInVault: (Boolean) -> Unit,
    storageGranted: Boolean,
    onRequestStorage: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val phoneRoot = remember { Environment.getExternalStorageDirectory() }
    val vaultRoot = remember { Fs.vaultRoot(ctx) }

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
    var authVault by remember { mutableStateOf(false) }
    var showFtp by remember { mutableStateOf(false) }
    var showReceive by remember { mutableStateOf(false) }
    var sendItems by remember { mutableStateOf<List<File>?>(null) }
    var vaultTab by remember { mutableIntStateOf(0) } // 0 — все, 1 — фото, 2 — видео, 3 — файлы
    val viewPrefs = remember { ViewPrefs(ctx) }
    var showViewDialog by remember { mutableStateOf(false) }
    var migrating by remember { mutableStateOf<String?>(null) }

    val listState = rememberLazyListState()
    val loader = remember {
        ImageLoader.Builder(ctx)
            .components {
                add(VaultKeyer())
                add(VaultFetcherFactory())
                add(VideoFrameDecoder.Factory())
            }
            .build()
    }
    val dateFmt = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }

    val root = if (inVault) vaultRoot else phoneRoot
    val dir = if (inVault) vaultDir else phoneDir
    val showList = inVault || storageGranted

    fun setDir(f: File) {
        if (inVault) vaultDir = f else phoneDir = f
        selected = emptySet()
    }

    fun toast(text: String) {
        Toast.makeText(ctx, text, Toast.LENGTH_LONG).show()
    }

    // Запрет скриншотов, пока открыт скрытый раздел или ввод его PIN
    val activity = ctx.findActivity()
    val secure = inVault || authVault
    DisposableEffect(secure) {
        if (secure) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }

    // Хранилище закрылось (приложение свернули): забываем буфер с его файлами и кэш миниатюр
    val vaultUnlocked = VaultCrypto.unlocked
    LaunchedEffect(vaultUnlocked) {
        if (!vaultUnlocked) {
            clip = clip?.takeIf { c -> c.files.none { VaultCrypto.isVaultPath(it) } }
            loader.memoryCache?.clear()
        }
    }

    // При входе в скрытый раздел и выходе из него сбрасываем выбор и просмотр
    LaunchedEffect(inVault) {
        viewer = null
        selected = emptySet()
        if (!inVault) {
            vaultDir = vaultRoot
            vaultTab = 0
        }
    }

    val entries = remember(dir, refreshKey, byDate, showList, inVault, vaultTab) {
        when {
            !showList -> emptyList()
            inVault && vaultTab != 0 -> Fs.listAll(vaultRoot, vaultTab)
            else -> Fs.list(dir, byDate)
        }
    }

    fun onItem(f: File) {
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
    }

    LaunchedEffect(dir, inVault) { listState.scrollToItem(0) }

    // Скрытый вход: в обычном разделе потянуть список вверх, когда он уже доехал до конца
    val pullPx = with(LocalDensity.current) { 110.dp.toPx() }
    val onPull by rememberUpdatedState {
        if (!inVault && selected.isEmpty() && !authVault) authVault = true
    }

    val mainVisible = !authVault && !showFtp && viewer == null
    BackHandler(enabled = mainVisible && (selected.isNotEmpty() || dir != root || inVault || vaultTab != 0)) {
        when {
            selected.isNotEmpty() -> selected = emptySet()
            inVault && vaultTab != 0 -> vaultTab = 0
            dir != root -> dir.parentFile?.let { setDir(it) }
            inVault -> setInVault(false)
        }
    }

    val v = viewer
    when {
        authVault -> VaultGate(
            onSuccess = {
                scope.launch {
                    val need = withContext(Dispatchers.IO) { VaultCrypto.needsMigration() }
                    if (need) {
                        migrating = "Шифрование файлов…"
                        try {
                            withContext(Dispatchers.IO) {
                                VaultCrypto.migrate { d, t -> migrating = "Шифрование файлов: $d из $t" }
                            }
                        } catch (e: Exception) {
                            toast("Не все файлы удалось зашифровать: " + (e.message ?: ""))
                        }
                        migrating = null
                    }
                    authVault = false
                    setInVault(true)
                }
            },
            onCancel = { authVault = false }
        )
        showFtp -> FtpScreen(onClose = { showFtp = false })
        v != null -> {
            BackHandler { viewer = null }
            Viewer(files = v.first, start = v.second, loader = loader, onClose = { viewer = null })
        }
        else -> Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            when {
                                selected.isNotEmpty() -> "Выбрано: ${selected.size}"
                                dir == root -> if (inVault) "Хранилище" else "Проводник"
                                else -> dir.name
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.combinedClickable(
                                onClick = {},
                                onLongClick = {
                                    if (!inVault && selected.isEmpty()) authVault = true
                                }
                            )
                        )
                    },
                    actions = {
                        Box {
                            TextButton(onClick = { menuOpen = true }) { Text("☰", fontSize = 22.sp) }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                if (selected.isEmpty()) {
                                    if (showList && (!inVault || vaultTab == 0)) {
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
                                    }
                                    if (inVault && vaultTab != 0) {
                                        if (vaultTab in 1..2) {
                                            DropdownMenuItem(
                                                text = { Text("Вид миниатюр") },
                                                onClick = { menuOpen = false; showViewDialog = true }
                                            )
                                        }
                                        DropdownMenuItem(
                                            text = { Text("Обновить") },
                                            onClick = { menuOpen = false; refreshKey++ }
                                        )
                                    }
                                    DropdownMenuItem(
                                        text = { Text("Доступ по FTP") },
                                        onClick = { menuOpen = false; showFtp = true }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Принять файлы") },
                                        onClick = { menuOpen = false; showReceive = true }
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
                                    DropdownMenuItem(
                                        text = { Text("Отправить на другой телефон") },
                                        onClick = {
                                            menuOpen = false
                                            sendItems = selected.toList()
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
                )
            },
            bottomBar = {
                val c = clip
                if (c != null && showList && (!inVault || vaultTab == 0)) {
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
                                val targetVault = VaultCrypto.isVaultPath(target)
                                val cross = c.files.any { VaultCrypto.isVaultPath(it) != targetVault }
                                if (cross && !VaultCrypto.unlocked) {
                                    toast("Сначала откройте хранилище")
                                } else {
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
                    .pointerInput(pullPx, showList) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            val atEnd = !showList || !listState.canScrollForward
                            var dy = 0f
                            var fired = false
                            while (atEnd) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val c = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!c.pressed) break
                                dy += c.positionChange().y
                                if (!fired && -dy > pullPx) {
                                    fired = true
                                    onPull()
                                }
                            }
                        }
                    }
            ) {
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())

                if (inVault) {
                    TabRow(selectedTabIndex = vaultTab) {
                        listOf("Все", "Фото", "Видео", "Файлы").forEachIndexed { i, t ->
                            Tab(
                                selected = vaultTab == i,
                                onClick = { vaultTab = i; selected = emptySet() },
                                text = { Text(t) }
                            )
                        }
                    }
                }

                if (!showList) {
                    PermissionPrompt(onRequestStorage)
                } else {
                    val tabbed = inVault && vaultTab != 0
                    if (!tabbed) {
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
                                (if (inVault) "Хранилище" else "Память") + dir.path.removePrefix(root.path),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp)
                            )
                        }
                    }

                    if (tabbed && vaultTab in 1..2 && entries.isNotEmpty()) {
                        MediaCollection(
                            files = entries,
                            prefs = viewPrefs,
                            selected = selected,
                            loader = loader,
                            onClick = { onItem(it) },
                            onLongClick = { selected = selected + it }
                        )
                    } else {
                        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                            if (entries.isEmpty()) {
                                item {
                                    Box(
                                        Modifier.fillParentMaxSize(),
                                        contentAlignment = Alignment.Center
                                    ) { Text(if (tabbed) "Здесь пока ничего нет" else "Папка пуста") }
                                }
                            } else {
                                items(entries, key = { it.path }) { f ->
                                    FileRow(
                                        f = f,
                                        selected = f in selected,
                                        stamp = refreshKey,
                                        loader = loader,
                                        dateFmt = dateFmt,
                                        onClick = { onItem(f) },
                                        onLongClick = { selected = selected + f }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showNewFolder) {
        NameDialog(title = "Новая папка", initial = "", onDismiss = { showNewFolder = false }) { name ->
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
        NameDialog(title = "Переименовать", initial = rt.name, onDismiss = { renameTarget = null }) { name ->
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

    val si = sendItems
    if (si != null) {
        SendDialog(items = si, onDismiss = { sendItems = null })
    }

    if (showReceive) {
        ReceiveDialog(onDismiss = { showReceive = false })
    }

    val mg = migrating
    if (mg != null) {
        KeepScreenOn()
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Подождите") },
            text = { Text(mg) },
            confirmButton = {}
        )
    }

    if (showViewDialog) {
        ViewDialog(prefs = viewPrefs, onDismiss = { showViewDialog = false })
    }
}

@Composable
fun PermissionPrompt(onRequest: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(48.dp))
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
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
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
            "${Fs.formatSize(Fs.sizeOf(f))} · ${dateFmt.format(Date(f.lastModified()))}"
        }
    }

    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) Color(0x33FFFFFF) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            when {
                f.isDirectory -> Text("📁", fontSize = 28.sp)
                Fs.isMedia(f) -> {
                    AsyncImage(
                        model = Fs.imageModel(f),
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

// ───────────────────────── FTP ─────────────────────────

@Composable
fun FtpScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val running = FtpState.running
    val ips = remember(running) { localIps() }

    BackHandler(onBack = onClose)

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { FtpService.start(ctx) }

    fun startFtp() {
        FtpState.error = null
        val needPerm = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needPerm) permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) else FtpService.start(ctx)
    }

    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        TextButton(onClick = onClose) { Text("← Назад") }
        Text("Доступ по FTP", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Позволяет открыть память телефона с компьютера по Wi-Fi. " +
                "Телефон и компьютер должны быть в одной сети (или компьютер подключён к точке доступа телефона)."
        )
        Spacer(Modifier.height(16.dp))

        if (running) {
            Text("Сервер включён", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            if (ips.isEmpty()) {
                Text("Нет подключения к Wi-Fi. Подключитесь к сети или включите точку доступа.")
            } else {
                Text("Адрес для компьютера:")
                for (ip in ips) {
                    Text("ftp://$ip:${FtpState.PORT}", style = MaterialTheme.typography.titleMedium)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("Логин: ${FtpState.USER}")
            Text("Пароль: ${FtpState.password}")
            Spacer(Modifier.height(16.dp))
            Button(onClick = { FtpService.stop(ctx) }) { Text("Выключить") }
        } else {
            OutlinedTextField(
                value = FtpState.password,
                onValueChange = { FtpState.savePassword(ctx, it.filter { c -> !c.isWhitespace() }) },
                label = { Text("Пароль (не короче 4 символов)") },
                singleLine = true
            )
            Spacer(Modifier.height(4.dp))
            Text("Логин: ${FtpState.USER}")
            Spacer(Modifier.height(16.dp))
            Button(onClick = { startFtp() }, enabled = FtpState.password.length >= 4) {
                Text("Включить")
            }
        }

        val err = FtpState.error
        if (err != null) {
            Spacer(Modifier.height(8.dp))
            Text(err, color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(24.dp))
        Text("Как подключиться с Windows", fontWeight = FontWeight.Bold)
        Text(
            "Откройте «Этот компьютер», вставьте адрес в адресную строку проводника и нажмите Enter. " +
                "Введите логин и пароль. Можно также использовать FileZilla или другой FTP-клиент (порт ${FtpState.PORT})."
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Скрытый раздел по FTP недоступен. Передача идёт без шифрования — " +
                "пользуйтесь только в доверенной сети."
        )
    }
}

// ───────────────────────── Передача между телефонами ─────────────────────────

@Composable
fun SendDialog(items: List<File>, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var peers by remember { mutableStateOf<List<Peer>>(emptyList()) }
    var searching by remember { mutableStateOf(true) }
    var host by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }

    KeepScreenOn()

    fun search() {
        searching = true
        scope.launch {
            peers = withContext(Dispatchers.IO) { Share.discover() }
            searching = false
        }
    }

    LaunchedEffect(Unit) { search() }

    AlertDialog(
        onDismissRequest = {
            if (!sending) onDismiss()
        },
        title = { Text("Отправить на другой телефон") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "На втором телефоне откройте меню «⋮» → «Принять файлы» и введите показанный там код.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (searching) "Поиск устройств…" else "Найдено: ${peers.size}",
                        modifier = Modifier.weight(1f)
                    )
                    if (!searching) TextButton(onClick = { search() }) { Text("Повторить") }
                }
                for (p in peers) {
                    TextButton(onClick = { host = p.ip }) {
                        Text((if (host == p.ip) "✓ " else "") + p.name + " (" + p.ip + ")")
                    }
                }
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it.trim() },
                    label = { Text("Адрес получателя") },
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { v -> if (v.length <= 6 && v.all { it.isDigit() }) code = v },
                    label = { Text("Код с экрана получателя") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                val s = status
                if (s != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(s)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !sending && host.isNotBlank() && code.length == 6,
                onClick = {
                    sending = true
                    status = "Подключение…"
                    job = scope.launch {
                        try {
                            val files = withContext(Dispatchers.IO) { Fs.collect(items) }
                            if (files.isEmpty()) throw IllegalStateException("Нет файлов для отправки")
                            Share.send(host.trim(), code, files) { status = it }
                            status = "Готово: отправлено файлов ${files.size}"
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            status = "Ошибка: " + (e.message ?: e.javaClass.simpleName)
                        }
                        sending = false
                    }
                }
            ) { Text("Отправить") }
        },
        dismissButton = {
            TextButton(onClick = {
                job?.cancel()
                onDismiss()
            }) { Text(if (sending) "Отменить" else "Закрыть") }
        }
    )
}

@Composable
fun ReceiveDialog(onDismiss: () -> Unit) {
    val server = remember { ReceiveServer() }
    var err by remember { mutableStateOf<String?>(null) }
    val ips = remember { localIps() }

    KeepScreenOn()

    LaunchedEffect(Unit) {
        try {
            withContext(Dispatchers.IO) { server.start() }
        } catch (e: Exception) {
            err = "Не удалось начать приём: " + (e.message ?: e.javaClass.simpleName)
        }
    }
    DisposableEffect(Unit) {
        onDispose { server.stop() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Приём файлов") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Код для отправителя:")
                Text(server.code, fontSize = 36.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                if (ips.isNotEmpty()) {
                    Text(
                        "Адрес: " + ips.joinToString(", "),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(err ?: server.status)
                if (server.progress.isNotEmpty()) Text(server.progress)
                if (server.received.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("Получено:", fontWeight = FontWeight.Bold)
                    for (r in server.received.takeLast(8)) {
                        Text(r, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Файлы сохраняются в Загрузки/Получено. Оба телефона должны быть в одной Wi-Fi сети.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } }
    )
}

// ───────────────────────── Просмотр фото и видео ─────────────────────────

private val orientationNames = listOf("Авто", "Альбомная", "Книжная")
private val orientationValues = listOf(
    ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
    ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Viewer(files: List<File>, start: Int, loader: ImageLoader, onClose: () -> Unit) {
    val activity = LocalContext.current.findActivity()
    val pagerState = rememberPagerState(initialPage = start.coerceIn(0, files.lastIndex)) {
        files.size
    }
    var zoomed by remember { mutableStateOf(false) }
    var orient by remember { mutableIntStateOf(0) }

    LaunchedEffect(pagerState.currentPage) { zoomed = false }
    LaunchedEffect(orient) { activity?.requestedOrientation = orientationValues[orient] }
    DisposableEffect(Unit) {
        onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .systemBarsPadding()
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text("← Назад") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { orient = (orient + 1) % orientationValues.size }) {
                Text("⟳ " + orientationNames[orient])
            }
            Text(
                "${pagerState.currentPage + 1} / ${files.size}",
                color = Color.White,
                modifier = Modifier.padding(end = 16.dp)
            )
        }
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = !zoomed,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) { page ->
            val f = files[page]
            if (Fs.isVideo(f)) {
                VideoPage(file = f, isCurrent = pagerState.currentPage == page)
            } else {
                ZoomableImage(file = f, loader = loader) { z ->
                    if (pagerState.currentPage == page) zoomed = z
                }
            }
        }
    }
}

/** Фото: увеличение щипком, двойное касание — приблизить или вернуть, перемещение пальцем при увеличении. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ZoomableImage(file: File, loader: ImageLoader, onZoomChange: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }

    fun clamp(o: Offset, sc: Float): Offset {
        val mx = size.width * (sc - 1f) / 2f
        val my = size.height * (sc - 1f) / 2f
        return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
    }

    val state = rememberTransformableState { zoomChange, panChange, _ ->
        val ns = (scale * zoomChange).coerceIn(1f, 6f)
        scale = ns
        offset = if (ns <= 1f) Offset.Zero else clamp(offset + panChange, ns)
    }

    LaunchedEffect(scale > 1.01f) { onZoomChange(scale > 1.01f) }

    val request = remember(file) {
        ImageRequest.Builder(ctx).data(Fs.imageModel(file)).size(2400).build()
    }

    AsyncImage(
        model = request,
        imageLoader = loader,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    if (scale > 1.01f) {
                        scale = 1f
                        offset = Offset.Zero
                    } else {
                        scale = 2.5f
                    }
                })
            }
            .transformable(state = state, canPan = { scale > 1.01f })
            .graphicsLayer(
                scaleX = scale,
                scaleY = scale,
                translationX = offset.x,
                translationY = offset.y
            )
    )
}

private fun formatTime(ms: Long): String {
    val total = ms / 1000
    return String.format(Locale.US, "%d:%02d", total / 60, total % 60)
}

@OptIn(UnstableApi::class)
@Composable
fun VideoPage(file: File, isCurrent: Boolean) {
    val ctx = LocalContext.current
    val exo = remember(file) {
        val builder = ExoPlayer.Builder(ctx)
        if (VaultCrypto.isVaultPath(file)) {
            // файлы хранилища расшифровываются на лету, только в памяти
            builder.setMediaSourceFactory(
                DefaultMediaSourceFactory(DataSource.Factory { VaultDataSource() })
            )
        }
        builder.build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            prepare()
        }
    }

    var loopAll by remember { mutableStateOf(false) }
    var speedIndex by remember { mutableIntStateOf(1) }
    var pointA by remember { mutableStateOf<Long?>(null) }
    var pointB by remember { mutableStateOf<Long?>(null) }
    val speeds = remember { listOf(0.5f, 1f, 1.5f, 2f) }

    LaunchedEffect(isCurrent) {
        if (isCurrent) exo.play() else exo.pause()
    }
    LaunchedEffect(loopAll) {
        exo.repeatMode = if (loopAll) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }
    LaunchedEffect(speedIndex) { exo.setPlaybackSpeed(speeds[speedIndex]) }

    // Повтор отрезка: дошли до B — возвращаемся в A
    LaunchedEffect(pointA, pointB) {
        val a = pointA
        val b = pointB
        if (a != null && b != null) {
            while (true) {
                if (exo.isPlaying && exo.currentPosition >= b) exo.seekTo(a)
                delay(50)
            }
        }
    }

    DisposableEffect(exo) {
        onDispose { exo.release() }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { context ->
                PlayerView(context).apply {
                    player = exo
                    useController = true
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        Row(
            Modifier
                .align(Alignment.TopCenter)
                .background(Color(0x66000000)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { loopAll = !loopAll }) {
                Text(if (loopAll) "↻ Повтор ✓" else "↻ Повтор", fontSize = 13.sp)
            }
            TextButton(onClick = { speedIndex = (speedIndex + 1) % speeds.size }) {
                Text("×" + speeds[speedIndex].toString().removeSuffix(".0"), fontSize = 13.sp)
            }
            TextButton(onClick = {
                val pos = exo.currentPosition
                pointA = pos
                val b = pointB
                if (b != null && b <= pos) pointB = null
            }) {
                Text(pointA?.let { "A " + formatTime(it) } ?: "A", fontSize = 13.sp)
            }
            TextButton(onClick = {
                val pos = exo.currentPosition
                val a = pointA
                if (a == null || pos > a) pointB = pos
            }) {
                Text(pointB?.let { "B " + formatTime(it) } ?: "B", fontSize = 13.sp)
            }
            if (pointA != null || pointB != null) {
                TextButton(onClick = { pointA = null; pointB = null }) {
                    Text("Сброс", fontSize = 13.sp)
                }
            }
        }
    }
}

// ───────────────────────── Вид миниатюр (фото и видео) ─────────────────────────

class ViewPrefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("view_prefs", Context.MODE_PRIVATE)

    // 0 — список, 1 — сетка по 4, 2 — сетка по 2, 3 — крупные на всю ширину
    var mode by mutableIntStateOf(p.getInt("mode", 1))
    var showName by mutableStateOf(p.getBoolean("name", true))
    var showDate by mutableStateOf(p.getBoolean("date", false))

    fun save() {
        p.edit().putInt("mode", mode).putBoolean("name", showName).putBoolean("date", showDate).apply()
    }
}

@Composable
fun ViewDialog(prefs: ViewPrefs, onDismiss: () -> Unit) {
    val labels = listOf(
        "Список: миниатюра, название и дата в строку",
        "Сетка, 4 в ряд",
        "Сетка, 2 в ряд",
        "Крупные, на всю ширину"
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Вид миниатюр") },
        text = {
            Column {
                labels.forEachIndexed { i, label ->
                    TextButton(onClick = { prefs.mode = i; prefs.save() }) {
                        Text((if (prefs.mode == i) "● " else "○ ") + label)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = prefs.showName,
                        onCheckedChange = { prefs.showName = it; prefs.save() }
                    )
                    Text("Показывать название")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = prefs.showDate,
                        onCheckedChange = { prefs.showDate = it; prefs.save() }
                    )
                    Text("Показывать дату")
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Готово") } }
    )
}

@Composable
fun Thumb(f: File, loader: ImageLoader, selected: Boolean, modifier: Modifier, fit: Boolean = false) {
    Box(modifier.background(Color(0xFF222222))) {
        AsyncImage(
            model = Fs.imageModel(f),
            imageLoader = loader,
            contentDescription = null,
            contentScale = if (fit) ContentScale.Fit else ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        if (Fs.isVideo(f)) {
            Text("▶", color = Color.White, fontSize = 22.sp, modifier = Modifier.align(Alignment.Center))
        }
        if (selected) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0x66FFFFFF)),
                contentAlignment = Alignment.Center
            ) { Text("✓", color = Color.Black, fontSize = 28.sp) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaCollection(
    files: List<File>,
    prefs: ViewPrefs,
    selected: Set<File>,
    loader: ImageLoader,
    onClick: (File) -> Unit,
    onLongClick: (File) -> Unit
) {
    val fullFmt = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }
    val shortFmt = remember { SimpleDateFormat("dd.MM.yy", Locale.getDefault()) }

    when (prefs.mode) {
        0 -> LazyColumn(Modifier.fillMaxSize()) {
            items(files, key = { it.path }) { f ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .combinedClickable(onClick = { onClick(f) }, onLongClick = { onLongClick(f) })
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Thumb(f, loader, f in selected, Modifier.size(64.dp))
                    if (prefs.showName || prefs.showDate) {
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            if (prefs.showName) {
                                Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (prefs.showDate) {
                                Text(
                                    fullFmt.format(Date(f.lastModified())),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        1, 2 -> LazyVerticalGrid(
            columns = GridCells.Fixed(if (prefs.mode == 1) 4 else 2),
            contentPadding = PaddingValues(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            gridItems(files, key = { it.path }) { f ->
                Column(
                    Modifier.combinedClickable(onClick = { onClick(f) }, onLongClick = { onLongClick(f) })
                ) {
                    Thumb(f, loader, f in selected, Modifier.fillMaxWidth().aspectRatio(1f))
                    if (prefs.showName) {
                        Text(
                            f.name,
                            fontSize = if (prefs.mode == 1) 10.sp else 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (prefs.showDate) {
                        Text(
                            (if (prefs.mode == 1) shortFmt else fullFmt).format(Date(f.lastModified())),
                            fontSize = if (prefs.mode == 1) 9.sp else 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
            }
        }

        else -> LazyColumn(Modifier.fillMaxSize()) {
            items(files, key = { it.path }) { f ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .combinedClickable(onClick = { onClick(f) }, onLongClick = { onLongClick(f) })
                        .padding(bottom = 12.dp)
                ) {
                    Thumb(f, loader, f in selected, Modifier.fillMaxWidth().aspectRatio(4f / 3f), fit = true)
                    if (prefs.showName || prefs.showDate) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (prefs.showName) {
                                Text(
                                    f.name,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            } else {
                                Spacer(Modifier.weight(1f))
                            }
                            if (prefs.showDate) {
                                Text(
                                    fullFmt.format(Date(f.lastModified())),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

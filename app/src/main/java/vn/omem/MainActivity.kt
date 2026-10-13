package vn.omem

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.util.Size
import android.util.LruCache
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Scheduler.apply(this)
        setContent { MaterialTheme { App() } }
    }
}

fun fmt(b: Long): String = when {
    b >= 1L shl 40 -> "%.1f TB".format(b / 1099511627776.0)
    b >= 1L shl 30 -> "%.2f GB".format(b / 1073741824.0)
    b >= 1L shl 20 -> "%.1f MB".format(b / 1048576.0)
    else -> "${b / 1024} KB"
}

object Thumbs {
    private val cache = LruCache<String, Bitmap>(120)
    fun load(c: android.content.Context, id: String): Bitmap? {
        cache.get(id)?.let { return it }
        val b = Server.thumbnail(c, id)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        if (b != null) cache.put(id, b)
        return b
    }
}

@Composable
fun Thumb(e: Entry, modifier: Modifier, selecting: Boolean = false, selected: Boolean = false) {
    val ctx = LocalContext.current
    val bmp by produceState<Bitmap?>(null, e.id) {
        value = if (e.hasThumb) withContext(Dispatchers.IO) { runCatching { Thumbs.load(ctx, e.id) }.getOrNull() } else null
    }
    Box(modifier.background(Color(0xFFE0E0E0)), contentAlignment = Alignment.Center) {
        bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            ?: Text(if (e.mime.startsWith("video/")) "▶" else "🖼")
        if (e.mime.startsWith("video/")) Text("▶", color = Color.White, modifier = Modifier.align(Alignment.BottomStart).padding(4.dp))
        if (selected) Box(Modifier.fillMaxSize().background(Color(0x3300AAFF)))
        if (selecting) Box(
            Modifier.align(Alignment.TopStart).padding(6.dp).size(22.dp)
                .background(if (selected) Color(0xFF1E88E5) else Color(0x66000000), CircleShape),
            contentAlignment = Alignment.Center
        ) { if (selected) Text("✓", color = Color.White, fontSize = 14.sp) }
    }
}

object LocalThumbs { val cache = LruCache<Long, Bitmap>(150) }

/** Ảnh/video trên máy chưa sao lưu xong: hiện ngay trong thư viện, kèm dấu đang chờ / đang tải lên (giống Google Photos). */
@Composable
fun LocalThumb(item: Item, uploading: Boolean, modifier: Modifier) {
    val ctx = LocalContext.current
    val bmp by produceState<Bitmap?>(LocalThumbs.cache.get(item.id), item.id) {
        if (value == null) value = withContext(Dispatchers.IO) {
            runCatching {
                Compat.thumbnail(ctx, item.id, item.isVideo, 320)?.also { LocalThumbs.cache.put(item.id, it) }
            }.getOrNull()
        }
    }
    Box(modifier.background(Color(0xFFE0E0E0)), contentAlignment = Alignment.Center) {
        bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            ?: Text(if (item.isVideo) "▶" else "🖼")
        if (item.isVideo) Text("▶", color = Color.White, modifier = Modifier.align(Alignment.BottomStart).padding(4.dp))
        Box(
            Modifier.align(Alignment.BottomEnd).padding(4.dp).size(22.dp).background(Color(0x99000000), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (uploading) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Color.White)
            else Text("☁", color = Color.White, fontSize = 12.sp)
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun App() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var accounts by remember { mutableStateOf(listOf<Acc>()) }
    var lib by remember { mutableStateOf(listOf<Group>()) }
    var wifi by remember { mutableStateOf(Store.wifiOnly(ctx)) }
    var auto by remember { mutableStateOf(Store.auto(ctx)) }
    var msg by remember { mutableStateOf("") }
    var tick by remember { mutableIntStateOf(0) }
    var tab by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<Group?>(null) }
    var confirmDelete by remember { mutableStateOf<List<Group>?>(null) }
    var sel by remember { mutableStateOf(setOf<String>()) }          // các tệp đang được chọn (theo grp)
    var viewing by remember { mutableStateOf<ViewSource?>(null) }
    var libLoaded by remember { mutableStateOf(false) }
    var local by remember { mutableStateOf(listOf<Item>()) }
    val uploading by SyncState.uploading.collectAsState()

    // Ảnh/video trên máy chưa sao lưu: quét lại mỗi 4 giây để ảnh vừa chụp hiện ngay
    LaunchedEffect(lib, libLoaded) {
        if (!libLoaded) return@LaunchedEffect
        var lastKick = 0L
        while (true) {
            local = withContext(Dispatchers.IO) { runCatching { MediaScan.notBackedUp(ctx, lib) }.getOrDefault(local) }
            // Đang mở app mà có tệp chờ: bắt đầu sao lưu luôn (tối đa 1 lần/phút, không chạy trùng)
            val now = System.currentTimeMillis()
            if (local.isNotEmpty() && SyncState.uploading.value.isEmpty() && Store.auto(ctx) && now - lastKick > 60_000) {
                lastKick = now; Scheduler.now(ctx)
            }
            delay(4000)
        }
    }
    // Đang tải lên thì làm mới thư viện thường xuyên hơn để tệp xong chuyển sang "đã sao lưu"
    LaunchedEffect(uploading.isNotEmpty()) {
        if (uploading.isNotEmpty()) while (true) { delay(8000); tick++ } else tick++
    }

    // Tự làm mới mỗi 30 giây để tài khoản mới thêm (kể cả bên server) được bật theo
    LaunchedEffect(Unit) { while (true) { delay(30_000); tick++ } }

    // Mỗi lần tick đổi thì tải lại, nhưng KHÔNG hủy lần đang tải dở (snapshotFlow gộp các lần yêu cầu dồn dập)
    LaunchedEffect(Unit) {
        snapshotFlow { tick }.collect {
            try {
                accounts = withContext(Dispatchers.IO) { Server.accountsAutoEnabled(ctx) }
                lib = withContext(Dispatchers.IO) { Server.library(ctx) }
                libLoaded = true
                if (msg.startsWith("Lỗi")) msg = ""
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) { msg = "Lỗi: ${e.message}" }
        }
    }

    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    LaunchedEffect(Unit) {
        perms.launch(
            if (Build.VERSION.SDK_INT >= 33)
                arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
            else if (Build.VERSION.SDK_INT <= 28)
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        )
    }

    val enabled = accounts.filter { it.enabled && it.ok }
    val total = enabled.sumOf { it.limit }
    val used = enabled.sumOf { it.usage }
    val titles = listOf("Ảnh", "Video", "Tệp", "Cài đặt")
    fun kind(g: Group) = when {
        g.head.mime.startsWith("image/") -> 0
        g.head.mime.startsWith("video/") -> 1
        else -> 2
    }

    // Thoát chế độ chọn bằng nút Back
    BackHandler(enabled = sel.isNotEmpty()) { sel = emptySet() }
    val selGroups = lib.filter { it.head.grp in sel }

    fun downloadAll(gs: List<Group>) {
        scope.launch {
            var ok = 0; var err = ""
            gs.forEachIndexed { i, g ->
                msg = "Đang tải ${i + 1}/${gs.size}…"
                try { withContext(Dispatchers.IO) { Restore.run(ctx, g) }; ok++ }
                catch (ex: kotlinx.coroutines.CancellationException) { throw ex }
                catch (ex: Exception) { err = ex.message ?: "lỗi" }
            }
            msg = if (err.isEmpty()) "Đã lưu $ok tệp vào Download/OMem" else "Đã lưu $ok/${gs.size} tệp, lỗi: $err"
        }
    }

    // Tab "Tệp": bấm vào tệp hiện hộp Tải về / Xóa (tệp không xem được trong app)
    selected?.let { g ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(g.head.name, maxLines = 2) },
            text = { Text(fmt(g.head.size) + if (g.parts.size > 1) " • chia ${g.parts.size} phần" else "") },
            confirmButton = { TextButton(onClick = { selected = null; downloadAll(listOf(g)) }) { Text("Tải về") } },
            dismissButton = { TextButton(onClick = { confirmDelete = listOf(g); selected = null }) { Text("Xóa") } }
        )
    }
    confirmDelete?.let { gs ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Xóa vĩnh viễn?") },
            text = {
                Text((if (gs.size == 1) gs[0].head.name else "${gs.size} mục đã chọn") +
                    " sẽ bị xóa khỏi Google Drive và thư viện Ổ mềm. Bản gốc trên máy không bị ảnh hưởng.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null; sel = emptySet()
                    scope.launch {
                        var ok = 0; var err = ""
                        gs.forEachIndexed { i, g ->
                            msg = "Đang xóa ${i + 1}/${gs.size}…"
                            try { withContext(Dispatchers.IO) { Server.deleteGroup(ctx, g.head.grp) }; ok++ }
                            catch (ex: kotlinx.coroutines.CancellationException) { throw ex }
                            catch (ex: Exception) { err = ex.message ?: "lỗi" }
                        }
                        tick++
                        msg = if (err.isEmpty()) "Đã xóa $ok mục" else "Đã xóa $ok/${gs.size} mục, lỗi: $err"
                    }
                }) { Text("Xóa") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Huỷ") } }
        )
    }
    viewing?.let { v -> Viewer(v) { viewing = null } }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        // Nút quay về Desktop Mini (app con của Desktop Mini)
        if (sel.isEmpty()) TextButton(onClick = { (ctx as? android.app.Activity)?.finish() }) { Text("‹ Desktop Mini") }
        if (sel.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { sel = emptySet() }) { Text("✕") }
                Text("Đã chọn ${sel.size}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { downloadAll(selGroups); sel = emptySet() }) { Text("⬇ Tải") }
                TextButton(onClick = { confirmDelete = selGroups }) { Text("🗑 Xóa") }
            }
        }
        if (msg.isNotEmpty()) Text(msg, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(16.dp, 8.dp))

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (tab < 3) {
                val list = lib.filter { kind(it) == tab }
                val pend = if (tab < 2) local.filter { if (tab == 0) it.isImage else it.isVideo } else emptyList()
                Column(Modifier.fillMaxSize()) {
                    Text("${titles[tab]} đã sao lưu (${list.size}) • ${fmt(list.sumOf { it.head.size })}",
                        style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp, 8.dp))
                    if (pend.isNotEmpty()) {
                        val n = pend.count { it.id in uploading }
                        Text("☁ ${pend.size} đang chờ sao lưu" + if (n > 0) " • đang tải lên $n" else "",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(16.dp, 0.dp, 16.dp, 8.dp))
                    }
                    if (list.isEmpty() && pend.isEmpty()) Text("Chưa có ${titles[tab].lowercase()} nào.", modifier = Modifier.padding(16.dp))
                    if (tab < 2) {
                        LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxSize().padding(horizontal = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(pend, key = { "L${it.id}" }) { f ->
                                val up = f.id in uploading
                                LocalThumb(f, up, Modifier.aspectRatio(1f).clickable {
                                    if (sel.isEmpty()) viewing = ViewSource.Local(f)
                                })
                            }
                            items(list, key = { "S${it.head.grp}" }) { g ->
                                val on = g.head.grp in sel
                                Thumb(g.head, Modifier.aspectRatio(1f).combinedClickable(
                                    onClick = {
                                        if (sel.isNotEmpty()) sel = if (on) sel - g.head.grp else sel + g.head.grp
                                        else viewing = ViewSource.Remote(g)
                                    },
                                    onLongClick = { sel = if (on) sel - g.head.grp else sel + g.head.grp }
                                ), selecting = sel.isNotEmpty(), selected = on)
                            }
                        }
                    } else {
                        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(list, key = { it.head.grp }) { g ->
                                val on = g.head.grp in sel
                                Column(Modifier.fillMaxWidth().background(if (on) Color(0x3300AAFF) else Color.Transparent).combinedClickable(
                                    onClick = {
                                        if (sel.isNotEmpty()) sel = if (on) sel - g.head.grp else sel + g.head.grp
                                        else selected = g
                                    },
                                    onLongClick = { sel = if (on) sel - g.head.grp else sel + g.head.grp }
                                )) {
                                    Text((if (on) "✓ " else "") + g.head.name, maxLines = 1)
                                    Text(fmt(g.head.size) + if (g.parts.size > 1) " • chia ${g.parts.size} phần" else "",
                                        style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Text("Ổ mềm", style = MaterialTheme.typography.headlineMedium)
                                Text("Tổng dung lượng gộp: ${fmt(total)}")
                                Text("Còn trống ${fmt((total - used).coerceAtLeast(0))}")
                                LinearProgressIndicator(progress = { if (total > 0) used.toFloat() / total else 0f },
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                            }
                        }
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                // Đăng nhập Google trên trang của server; bạn tự nhập email + mật khẩu ở đó
                                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Store.serverUrl(ctx).trimEnd('/') + "/api/auth/google/start")))
                                msg = "Đăng nhập xong, quay lại đây, app sẽ tự bật tài khoản mới"
                            }) { Text("Thêm tài khoản Google") }
                            OutlinedButton(onClick = { Scheduler.now(ctx); msg = "Đang sao lưu nền…" }) { Text("Sao lưu ngay") }
                        }
                    }
                    item { Text("Lần sao lưu gần nhất: " + remember(tick) { Store.lastRun(ctx) }.ifBlank { "chưa chạy" }, style = MaterialTheme.typography.bodySmall) }
                    item { Text("Tài khoản (tự động bật hết)", style = MaterialTheme.typography.titleSmall) }
                    items(accounts, key = { it.email }) { a ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text(a.email)
                                Text(
                                    when {
                                        !a.ok -> "Không đọc được dung lượng"
                                        else -> "${fmt(a.usage)} / ${fmt(a.limit)}"
                                    } + if (a.enabled) " • đang bật" else " • chưa bật (tối đa $MAX_ACCOUNTS tài khoản)",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(auto, { auto = it; Store.setAuto(ctx, it); Scheduler.apply(ctx, true) })
                            Text("  Tự sao lưu khi có mạng")
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(wifi, { wifi = it; Store.setWifiOnly(ctx, it); Scheduler.apply(ctx, true) })
                            Text("  Chỉ dùng Wi-Fi")
                        }
                        val pm = ctx.getSystemService(android.os.PowerManager::class.java)
                        if (!pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
                            OutlinedButton(onClick = {
                                ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
                            }) { Text("Cho phép chạy nền (để tự sao lưu ổn định)") }
                        }
                        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
                            OutlinedButton(onClick = {
                                ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}")))
                            }) { Text("Cấp quyền truy cập mọi tệp (sao lưu cả tài liệu)") }
                        }
                        TextButton(onClick = { tick++ }) { Text("Làm mới") }
                    }
                }
            }
        }

        HorizontalDivider()
        TabRow(selectedTabIndex = tab, modifier = Modifier.navigationBarsPadding()) {
            titles.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i; sel = emptySet() }, text = { Text(t) }) }
        }
    }
}

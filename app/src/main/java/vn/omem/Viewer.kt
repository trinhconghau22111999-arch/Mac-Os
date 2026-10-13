package vn.omem

import android.content.ContentUris
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Nguồn để xem: tệp trên máy (chưa sao lưu xong) hoặc tệp đã sao lưu trên Drive. */
sealed class ViewSource {
    data class Local(val item: Item) : ViewSource()
    data class Remote(val g: Group) : ViewSource()
}

/** Xem toàn màn hình ảnh (phóng to bằng hai ngón) hoặc phát video. */
@Composable
fun Viewer(src: ViewSource, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val name = when (src) { is ViewSource.Local -> src.item.name; is ViewSource.Remote -> src.g.head.name }
    val isVideo = when (src) { is ViewSource.Local -> src.item.isVideo; is ViewSource.Remote -> src.g.head.mime.startsWith("video/") }
    var error by remember(src) { mutableStateOf<String?>(null) }

    // Tệp trên Drive được tải về bộ nhớ đệm trước rồi mới mở
    val uri by produceState<Uri?>(null, src) {
        try {
            value = when (src) {
                is ViewSource.Local -> ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), src.item.id)
                is ViewSource.Remote -> withContext(Dispatchers.IO) { Uri.fromFile(Restore.toCache(ctx, src.g)) }
            }
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) { error = e.message ?: "không mở được" }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            val u = uri
            when {
                error != null -> Text("Lỗi: $error", color = Color.White, modifier = Modifier.align(Alignment.Center).padding(24.dp))
                u == null -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.White)
                    Text(if (src is ViewSource.Remote) "Đang tải về để xem…" else "Đang mở…", color = Color.White, modifier = Modifier.padding(top = 12.dp))
                }
                isVideo -> AndroidView(
                    factory = { c ->
                        VideoView(c).apply {
                            val mc = MediaController(c); mc.setAnchorView(this); setMediaController(mc)
                            setVideoURI(u); setOnPreparedListener { start() }
                        }
                    },
                    onRelease = { it.stopPlayback() },
                    modifier = Modifier.fillMaxSize()
                )
                else -> ZoomImage(u)
            }
            Row(Modifier.align(Alignment.TopStart).statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onClose) { Text("✕", color = Color.White) }
                Text(name, color = Color.White, maxLines = 1, modifier = Modifier.padding(end = 12.dp))
            }
        }
    }
}

@Composable
private fun ZoomImage(uri: Uri) {
    val ctx = LocalContext.current
    val res by produceState<Result<Bitmap>?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                Compat.decodeForView(ctx, uri)
            }
        }
    }
    val r = res
    when {
        r == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color.White) }
        r.isFailure -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Không mở được ảnh này", color = Color.White)
        }
        else -> {
            var scale by remember { mutableFloatStateOf(1f) }
            var off by remember { mutableStateOf(Offset.Zero) }
            Image(
                r.getOrThrow().asImageBitmap(), null,
                Modifier.fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 5f)
                            off = if (scale <= 1f) Offset.Zero else off + pan
                        }
                    }
                    .graphicsLayer { scaleX = scale; scaleY = scale; translationX = off.x; translationY = off.y },
                contentScale = ContentScale.Fit
            )
        }
    }
}

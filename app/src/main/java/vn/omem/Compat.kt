package vn.omem

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.annotation.RequiresApi
import java.io.InputStream

/** Hàm tương thích: chạy được từ Android 7.0 (API 24) đến Android mới nhất. */
object Compat {
    fun fileUri(id: Long): Uri = ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), id)

    /** Ảnh thu nhỏ (ảnh hoặc video) của tệp trong MediaStore. Trả về null nếu không tạo được. */
    fun thumbnail(ctx: Context, id: Long, isVideo: Boolean, px: Int = 320): Bitmap? =
        if (Build.VERSION.SDK_INT >= 29) thumbnail29(ctx, id, px) else thumbnailLegacy(ctx, id, isVideo, px)

    @RequiresApi(29)
    private fun thumbnail29(ctx: Context, id: Long, px: Int): Bitmap =
        ctx.contentResolver.loadThumbnail(fileUri(id), Size(px, px), null)

    private fun thumbnailLegacy(ctx: Context, id: Long, isVideo: Boolean, px: Int): Bitmap? {
        val uri = fileUri(id)
        if (!isVideo) return decodeImage(ctx, uri, px)
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, uri)
            r.getFrameAtTime(0)?.let { scaleDown(it, px) }
        } finally {
            runCatching { r.release() }
        }
    }

    /** Giải mã ảnh bằng BitmapFactory (có giảm kích thước + xoay theo EXIF), cạnh dài tối đa ~target. */
    fun decodeImage(ctx: Context, uri: Uri, target: Int): Bitmap? {
        val cr = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (longest / (sample * 2) >= target) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val rot = runCatching { cr.openInputStream(uri)?.use { exifRotation(it) } }.getOrNull() ?: 0
        val scaled = scaleDown(bmp, target)
        if (rot == 0) return scaled
        val m = Matrix().apply { postRotate(rot.toFloat()) }
        return Bitmap.createBitmap(scaled, 0, 0, scaled.width, scaled.height, m, true)
    }

    private fun exifRotation(s: InputStream): Int =
        when (ExifInterface(s).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }

    private fun scaleDown(b: Bitmap, target: Int): Bitmap {
        val m = maxOf(b.width, b.height)
        if (m <= target) return b
        val s = target.toFloat() / m
        return Bitmap.createScaledBitmap(
            b, (b.width * s).toInt().coerceAtLeast(1), (b.height * s).toInt().coerceAtLeast(1), true
        )
    }
}

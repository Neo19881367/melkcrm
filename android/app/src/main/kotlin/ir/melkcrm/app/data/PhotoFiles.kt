package ir.melkcrm.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import ir.melkcrm.client.remote.RemoteApi
import ir.melkcrm.client.sync.ImageFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Property photos in app-private storage (not readable by other apps, excluded from backups).
 * Imported photos are re-encoded as JPEG at most [MAX_EDGE] pixels on the long edge; re-encoding
 * also drops EXIF metadata, so a photo never carries the phone's GPS position to the server.
 */
class PhotoFiles(private val context: Context) : ImageFiles {
    private val photoDir get() = File(context.filesDir, "photos").apply { mkdirs() }
    private val cacheDir get() = File(context.cacheDir, "remote-photos").apply { mkdirs() }

    override suspend fun read(path: String): ByteArray? = withContext(Dispatchers.IO) {
        val f = File(path)
        if (f.isFile && f.canonicalPath.startsWith(context.filesDir.canonicalPath)) f.readBytes() else null
    }

    override fun contentType(path: String): String = when (path.substringAfterLast('.').lowercase()) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        else -> "image/jpeg"
    }

    /** Copies a picked image into private storage. Returns the stored path, or null if it is not a readable image. */
    suspend fun import(uri: Uri): String? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return@withContext null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_EDGE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return@withContext null
        val rotation = resolver.openInputStream(uri)?.use { s ->
            when (ExifInterface(s).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
        val scale = minOf(1f, MAX_EDGE.toFloat() / maxOf(decoded.width, decoded.height))
        val m = Matrix().apply { postScale(scale, scale); postRotate(rotation) }
        val bitmap = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)
        val out = File(photoDir, UUID.randomUUID().toString() + ".jpg")
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        if (bitmap !== decoded) bitmap.recycle()
        decoded.recycle()
        out.absolutePath
    }

    suspend fun delete(path: String?) = withContext(Dispatchers.IO) {
        if (path != null) File(path).takeIf { it.canonicalPath.startsWith(context.filesDir.canonicalPath) }?.delete()
        Unit
    }

    /** A server photo, downloaded once with the user's session and cached privately. */
    suspend fun remote(api: RemoteApi?, id: String): File? = withContext(Dispatchers.IO) {
        val f = File(cacheDir, id.filter { it.isLetterOrDigit() || it == '-' } + ".img")
        if (f.isFile && f.length() > 0) return@withContext f
        val bytes: ByteArray? = try {
            api?.downloadImage(id)
        } catch (e: Exception) {
            null
        }
        if (bytes == null) return@withContext null
        f.writeBytes(bytes)
        f
    }

    /** Removes every stored and cached photo (sign-out). */
    suspend fun wipe() = withContext(Dispatchers.IO) {
        photoDir.deleteRecursively()
        cacheDir.deleteRecursively()
        Unit
    }

    private companion object {
        const val MAX_EDGE = 1600
    }
}

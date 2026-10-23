package tv.minimal.home

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.security.MessageDigest

/** Copies user-selected images; never retains provider permissions or trusts provider filenames. */
internal object WallpaperImporter {
    private const val MAX_BYTES = 32L * 1024 * 1024

    fun importImage(context: Context, uri: Uri): Boolean {
        val dir = context.getExternalFilesDir("wallpapers") ?: return false
        if (!dir.exists() && !dir.mkdirs()) return false
        var part: File? = null
        return try {
            val temporary = File.createTempFile("import-", ".part", dir)
            part = temporary
            val digest = MessageDigest.getInstance("SHA-256")
            context.contentResolver.openInputStream(uri)?.use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var size = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        size += count
                        check(size <= MAX_BYTES) { "Image exceeds import size limit" }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                }
            } ?: return false
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(temporary.path, bounds)
            check(bounds.outWidth > 0 && bounds.outHeight > 0) { "Invalid image" }
            val extension = when (bounds.outMimeType) {
                "image/jpeg" -> "jpg"
                "image/png" -> "png"
                "image/webp" -> "webp"
                else -> error("Unsupported image format")
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            val displayName = runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                }
            }.getOrNull().orEmpty().substringBeforeLast('.')
                .filter { it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_' }
                .trim().take(40).ifBlank { "Eigenes Bild" }
            val destination = File(dir, "$displayName-${hash.take(12)}.$extension")
            destination.exists() || temporary.renameTo(destination)
        } catch (_: Exception) {
            false
        } finally {
            part?.delete()
        }
    }
}

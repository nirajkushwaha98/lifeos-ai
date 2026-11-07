package com.lifeos.ai.data.local.files

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.lifeos.ai.core.common.newId
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

data class ImportedFile(val storedName: String, val displayName: String, val mimeType: String, val size: Long)

/**
 * Stores user documents and profile photos in app-private internal storage (`filesDir`).
 * Files are never written to shared/public storage and never exposed via public URLs.
 */
@Singleton
class PrivateFileStore @Inject constructor(@ApplicationContext private val context: Context) {

    val documentsDir: File get() = File(context.filesDir, "documents").apply { mkdirs() }
    private val profileDir: File get() = File(context.filesDir, "profile").apply { mkdirs() }

    fun documentFile(storedName: String): File {
        val file = File(documentsDir, storedName)
        require(file.canonicalPath.startsWith(documentsDir.canonicalPath)) { "Invalid path" }
        return file
    }

    /** Copies a SAF uri into private storage. Max 25 MB. */
    suspend fun importDocument(uri: Uri): ImportedFile = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        require(mime == "application/pdf" || mime.startsWith("image/")) { "Unsupported type" }
        var displayName = "document"
        var declaredSize = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { displayName = it }
                if (!c.isNull(1)) declaredSize = c.getLong(1)
            }
        }
        if (declaredSize > MAX_SIZE) throw IOException("File too large")
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "bin"
        val storedName = "${newId()}.$ext"
        val target = documentFile(storedName)
        var copied = 0L
        resolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    copied += read
                    if (copied > MAX_SIZE) {
                        output.close(); target.delete()
                        throw IOException("File too large")
                    }
                    output.write(buffer, 0, read)
                }
            }
        } ?: throw IOException("Cannot open file")
        ImportedFile(storedName, displayName.substringBeforeLast('.').ifBlank { displayName }, mime, copied)
    }

    fun deleteDocument(storedName: String) {
        runCatching { documentFile(storedName).delete() }
    }

    suspend fun saveProfileImage(uri: Uri): String = withContext(Dispatchers.IO) {
        val target = File(profileDir, "avatar_${System.currentTimeMillis()}.jpg")
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { input.copyTo(it) }
        } ?: throw IOException("Cannot open image")
        profileDir.listFiles()?.filter { it != target }?.forEach { it.delete() }
        target.absolutePath
    }

    fun deleteProfileImages() {
        profileDir.listFiles()?.forEach { it.delete() }
    }

    fun clearCache(): Long {
        var freed = 0L
        context.cacheDir.walkBottomUp().forEach { f ->
            if (f != context.cacheDir) {
                if (f.isFile) freed += f.length()
                f.delete()
            }
        }
        return freed
    }

    fun cacheSize(): Long = context.cacheDir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    companion object {
        const val MAX_SIZE = 25L * 1024 * 1024
        private const val DEFAULT_BUFFER = 64 * 1024
    }
}

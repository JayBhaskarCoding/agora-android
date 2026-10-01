package com.example.agora.media

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * 🌟 Bridge to the device's NATIVE photo editor (Samsung Gallery, Google
 * Photos, etc.) — replaces the old custom Compose cropper so users get the
 * full flagship toolset: AI object erasure, markup, native filters.
 *
 * Output contract (identical to the old cropper's): the edited photo comes
 * back as a cache-dir file Uri, so the Coil previews and the Supabase /
 * Cloudinary upload pipeline consume it unchanged.
 *
 * Flow: copy the picked image into a private cache working file → expose it
 * through FileProvider → ACTION_EDIT with read + write grants → the editor
 * overwrites the working file in place (or hands back its own result Uri) →
 * the result is snapshotted into a FRESH cache file whose new Uri swaps into
 * the media list IN PLACE (new Uri = guaranteed Coil cache miss = refreshed
 * preview; same list slot = the draft never reshuffles or loses items).
 */
object NativePhotoEditor {

    /** One in-flight native-edit handoff, held between launch and result. */
    class Session(
        val sourceUri: Uri,
        val workFile: File,
        val providerUri: Uri
    )

    private fun editDir(context: Context): File =
        File(context.cacheDir, "photo_edit").apply { mkdirs() }

    /** Copies [sourceUri] into a writable FileProvider-backed working file. */
    suspend fun prepare(context: Context, sourceUri: Uri): Session? = withContext(Dispatchers.IO) {
        try {
            val workFile = File(editDir(context), "edit_${UUID.randomUUID()}.jpg")
            val copied = context.contentResolver.openInputStream(sourceUri)?.use { input ->
                workFile.outputStream().use { output -> input.copyTo(output) }
                true
            } ?: false
            if (!copied || !workFile.exists() || workFile.length() == 0L) {
                workFile.delete()
                return@withContext null
            }
            val providerUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                workFile
            )
            Session(sourceUri, workFile, providerUri)
        } catch (e: java.util.concurrent.CancellationException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * ACTION_EDIT intent carrying the working-file Uri with the read + write
     * grants the external editor needs to load AND overwrite the photo.
     */
    fun buildIntent(session: Session): Intent =
        Intent(Intent.ACTION_EDIT).apply {
            setDataAndType(session.providerUri, "image/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }

    /**
     * Snapshots the editor's result into a fresh cache file and returns its
     * Uri. Prefers the Uri the editor handed back (some save their own copy
     * instead of overwriting in place), falls back to the working file.
     * Returns null only when neither source yields readable bytes.
     */
    suspend fun finalizeEdit(context: Context, session: Session, returnedUri: Uri?): Uri? =
        withContext(Dispatchers.IO) {
            try {
                val bytes = readUriBytes(context, returnedUri) ?: readFileBytes(session.workFile)
                session.workFile.delete()
                if (bytes == null || bytes.isEmpty()) return@withContext null
                val resultFile = File(editDir(context), "edited_${UUID.randomUUID()}.jpg")
                resultFile.writeBytes(bytes)
                Uri.fromFile(resultFile)
            } catch (e: java.util.concurrent.CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }

    private fun readUriBytes(context: Context, uri: Uri?): ByteArray? {
        if (uri == null) return null
        return try {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun readFileBytes(file: File): ByteArray? =
        try {
            if (file.exists()) file.readBytes() else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
}

package com.example.exifdateeditor

import android.content.ContentValues
import android.content.Context
import android.app.RecoverableSecurityException
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.os.Environment
import android.util.Log
import android.content.ContentUris
import java.util.Locale
import android.database.sqlite.SQLiteConstraintException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import androidx.documentfile.provider.DocumentFile

/**
 * Converts a list of PNG images to JPG, saving results next to the originals
 */
class PngToJpgConverter(
    private val context: Context,
    private val uris: List<Uri>
) {
    private val TAG = "PngToJpgConverter"
    var onProgress: (current: Int, total: Int, fileName: String) -> Unit = { _, _, _ -> }
    var onComplete: (result: BatchOperationResult) -> Unit = {}

    private data class WriteHandle(
        val output: OutputStream,
        val destUri: Uri? = null,
        val destFile: File? = null,
        val finalize: (() -> Unit)? = null
    )

    fun convertAll(quality: Int = 95) {
        CoroutineScope(Dispatchers.IO + Job()).launch {
            val results = mutableListOf<Pair<String, Boolean>>()
            val errorMessages = mutableMapOf<String, String>()

            for ((index, uri) in uris.withIndex()) {
                val fileName = getDisplayName(uri)
                try {
                    withContext(Dispatchers.Main) {
                        onProgress(index + 1, uris.size, fileName)
                    }

                    val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
                    if (mime != null && mime.lowercase() != "image/png") {
                        results.add(fileName to false)
                        errorMessages[fileName] = "Not a PNG image"
                        continue
                    }

                    val inputStream = context.contentResolver.openInputStream(uri)
                        ?: throw IllegalStateException("Cannot open input stream")

                    val pngBitmap = inputStream.use { BitmapFactory.decodeStream(it) }
                        ?: throw IllegalStateException("Failed to decode PNG")

                    // Handle transparency by drawing on white background before JPEG compression
                    val outBitmap = if (pngBitmap.hasAlpha()) {
                        val resultBmp = Bitmap.createBitmap(
                            pngBitmap.width,
                            pngBitmap.height,
                            Bitmap.Config.ARGB_8888
                        )
                        val canvas = Canvas(resultBmp)
                        canvas.drawColor(Color.WHITE)
                        canvas.drawBitmap(pngBitmap, 0f, 0f, null)
                        resultBmp
                    } else pngBitmap

                    val newName = replaceExtension(fileName, "jpg")
                    val handle = createSiblingWriteHandle(uri, newName)
                        ?: throw IllegalStateException("Cannot create destination JPG next to source: ${previewDestinationPath(uri, newName)}")

                    val saved = handle.output.use { os ->
                        outBitmap.compress(Bitmap.CompressFormat.JPEG, quality, os)
                    }

                    // Mark visible if needed
                    handle.finalize?.invoke()

                    if (!saved) throw IllegalStateException("Compression failed")

                    if (outBitmap !== pngBitmap) outBitmap.recycle()

                    // Verify target exists
                    val exists = destinationExists(handle)
                    if (!exists) throw IllegalStateException("JPG not found after write")

                    // Delete source PNG now that JPG exists
                    val deleted = deleteSource(uri)
                    if (!deleted) {
                        results.add(fileName to false)
                        errorMessages[fileName] = "Created JPG, but failed to delete PNG"
                    } else {
                        results.add(fileName to true)
                    }
                } catch (e: Exception) {
                    results.add(fileName to false)
                    errorMessages[fileName] = e.message ?: "Unknown error"
                }
            }

            val successCount = results.count { it.second }
            val failureCount = results.size - successCount
            val failedImages = results.filter { !it.second }.map { it.first }

            withContext(Dispatchers.Main) {
                onComplete(
                    BatchOperationResult(
                        successCount = successCount,
                        failureCount = failureCount,
                        failedImages = failedImages,
                        errorMessages = errorMessages
                    )
                )
            }
        }
    }

    private fun previewDestinationPath(src: Uri, newName: String): String {
        return try {
            when (src.scheme?.lowercase()) {
                "file" -> {
                    val path = src.path ?: return newName
                    val parent = File(path).parentFile
                    (parent?.absolutePath ?: "") + "/" + newName
                }
                "content" -> {
                    // Prefer absolute dir preview if resolvable
                    resolveAbsoluteTargetDir(src)?.let { absDir ->
                        return absDir.trimEnd('/') + "/" + newName
                    }
                    val cr = context.contentResolver
                    val projection = arrayOf(MediaStore.MediaColumns.RELATIVE_PATH)
                    var relativePath: String? = cr.query(src, projection, null, null, null)?.use {
                        if (it.moveToFirst()) {
                            val relIdx = it.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                            if (relIdx >= 0) it.getString(relIdx) else null
                        } else null
                    }
                    if (relativePath.isNullOrEmpty()) {
                        relativePath = deriveRelativePathFromDocumentUri(src)
                    }
                    if (relativePath.isNullOrEmpty()) {
                        relativePath = guessRelativePathFromAuthority(src)
                    }
                    if (relativePath.isNullOrEmpty()) relativePath = "Pictures/"
                    if (!relativePath.endsWith("/")) relativePath += "/"

                    val topDir = relativePath.substringBefore('/')
                    val isImagesTop = topDir.equals("DCIM", true) || topDir.equals("Pictures", true)
                    val isDownloadTop = topDir.equals("Download", true) || topDir.equals("Downloads", true)
                    val insertRelPath = when {
                        isImagesTop -> relativePath
                        isDownloadTop -> relativePath
                        else -> "Pictures/"
                    }
                    val vol = resolveVolumeName(src)
                    val root = if (vol != null && !vol.equals(MediaStore.VOLUME_EXTERNAL_PRIMARY, true)) {
                        "/storage/" + vol
                    } else Environment.getExternalStorageDirectory().absolutePath.trimEnd('/')
                    "$root/" + insertRelPath + newName
                }
                else -> newName
            }
        } catch (_: Exception) {
            newName
        }
    }

    private fun guessRelativePathFromAuthority(src: Uri): String? {
        return try {
            val auth = src.authority?.lowercase(Locale.US) ?: return null
            val path = src.path?.lowercase(Locale.US) ?: ""
            when {
                auth.contains("downloads") || path.contains("download/") -> "Download/"
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveVolumeName(src: Uri): String? {
        return try {
            if (android.provider.DocumentsContract.isDocumentUri(context, src)) {
                val docId = android.provider.DocumentsContract.getDocumentId(src)
                val colon = docId.indexOf(':')
                if (colon > 0) {
                    val vol = docId.substring(0, colon)
                    return if (vol.equals("primary", true)) MediaStore.VOLUME_EXTERNAL_PRIMARY else vol
                }
                // If raw absolute path, extract /storage/<vol>/ prefix
                if (docId.startsWith("raw:")) {
                    val raw = docId.removePrefix("raw:")
                    if (raw.startsWith("/storage/")) {
                        val rest = raw.removePrefix("/storage/")
                        val idx = rest.indexOf('/')
                        if (idx > 0) return rest.substring(0, idx)
                    }
                }
            }
            // Heuristic: if path hints at downloads on non-primary, we can't know exact UUID; return null
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun getDisplayName(uri: Uri): String {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use {
                if (it.moveToFirst()) {
                    val idx = it.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                    if (idx >= 0) return@use it.getString(idx)
                }
                null
            } ?: (uri.lastPathSegment ?: "image.png")
        } catch (_: Exception) {
            uri.lastPathSegment ?: "image.png"
        }
    }

    private fun replaceExtension(name: String, newExt: String): String {
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        return "$base.$newExt"
    }

    private fun createSiblingWriteHandle(src: Uri, newName: String): WriteHandle? {
        return try {
            // Prefer absolute filesystem path when we can safely resolve it (Android 10 allows direct writes)
            val absDir = resolveAbsoluteTargetDir(src)
            if (absDir != null) {
                Log.d(TAG, "Using absolute target dir: $absDir for ${getDisplayName(src)}")
                val fh = createFileSiblingHandleFromDir(absDir, newName)
                if (fh != null) return fh
                Log.d(TAG, "Absolute write failed, falling back to provider path")
            }
            when (src.scheme?.lowercase()) {
                "content" -> createMediaStoreSiblingHandle(src, newName)
                "file" -> createFileSiblingHandle(src, newName)
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun createMediaStoreSiblingHandle(src: Uri, newName: String): WriteHandle? {
        return try {
            val cr = context.contentResolver
            // Try to read RELATIVE_PATH from the source to create the new file in same folder
            val projection = arrayOf(
                MediaStore.MediaColumns.RELATIVE_PATH
            )
            var relativePath: String? = cr.query(src, projection, null, null, null)?.use {
                if (it.moveToFirst()) {
                    val relIdx = it.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                    if (relIdx >= 0) it.getString(relIdx) else null
                } else null
            }

            if (relativePath.isNullOrEmpty()) {
                // Fallback: derive from Document ID if this is a Document URI
                relativePath = deriveRelativePathFromDocumentUri(src)
            }

            if (relativePath.isNullOrEmpty()) {
                // Device-specific fallback: infer by authority/path
                relativePath = guessRelativePathFromAuthority(src)
            }

            if (relativePath.isNullOrEmpty()) {
                // Last resort: default to Pictures/
                relativePath = "Pictures/"
            } else if (!relativePath!!.endsWith("/")) {
                relativePath += "/"
            }

            // Choose the proper collection based on the top-level directory
            val topDir = relativePath?.substringBefore('/')?.trimEnd('/') ?: "Pictures"

            val isImagesTop = topDir.equals("DCIM", ignoreCase = true) || topDir.equals("Pictures", ignoreCase = true)
            val isDownloadTop = topDir.equals("Download", ignoreCase = true) || topDir.equals("Downloads", ignoreCase = true)

            val volumeName = resolveVolumeName(src)
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                when {
                    isImagesTop -> MediaStore.Images.Media.getContentUri(volumeName ?: MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    isDownloadTop -> MediaStore.Downloads.getContentUri(volumeName ?: MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    else -> MediaStore.Images.Media.getContentUri(volumeName ?: MediaStore.VOLUME_EXTERNAL_PRIMARY)
                }
            } else {
                if (isDownloadTop) MediaStore.Downloads.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }

            // Normalize RELATIVE_PATH we will actually insert with
            val insertRelPath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                when {
                    isImagesTop -> relativePath
                    isDownloadTop -> relativePath
                    else -> "Pictures/"
                }
            } else null

            Log.d(TAG, "createMediaStoreSiblingHandle: topDir=$topDir isImagesTop=$isImagesTop isDownloadTop=$isDownloadTop relPath=$relativePath insertRelPath=$insertRelPath volume=$volumeName collection=$collection")

            // Try to insert with the requested name first; if it conflicts, add (1), (2), ...
            val base = newName.substringBeforeLast('.')
            val ext = newName.substringAfterLast('.', "jpg")
            var suffix = 0
            while (suffix < 1000) {
                val candidate = if (suffix == 0) newName else "$base($suffix).$ext"
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, candidate)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.MediaColumns.RELATIVE_PATH, insertRelPath)
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                }
                Log.d(TAG, "Attempting insert: candidate=$candidate relPath=$insertRelPath")
                try {
                    val dest = cr.insert(collection, values)
                    if (dest == null) {
                        Log.d(TAG, "Insert returned null for $candidate; checking for stale entry")
                        if (tryCleanupStaleEntry(cr, collection, insertRelPath, candidate)) {
                            Log.d(TAG, "Stale entry removed for $candidate; retrying same name")
                            continue
                        } else {
                            Log.d(TAG, "No stale entry; treating as name conflict and retrying with suffix")
                            suffix++
                            continue
                        }
                    }
                    val os = cr.openOutputStream(dest)
                    if (os == null) {
                        // Clean up this placeholder row and try next name
                        try { cr.delete(dest, null, null) } catch (_: Exception) {}
                        Log.d(TAG, "openOutputStream returned null for $candidate; retrying with suffix")
                        suffix++
                        continue
                    }
                    val finalize: (() -> Unit)? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        { cr.update(dest, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) }
                    } else null
                    return WriteHandle(output = os, destUri = dest, finalize = finalize)
                } catch (e: Exception) {
                    val isUnique = e is SQLiteConstraintException || (e.message?.contains("UNIQUE constraint failed", true) == true)
                    if (isUnique) {
                        Log.d(TAG, "Name conflict for $candidate; checking for stale entry")
                        if (tryCleanupStaleEntry(cr, collection, insertRelPath, candidate)) {
                            Log.d(TAG, "Stale entry removed for $candidate; retrying same name")
                            continue
                        } else {
                            Log.d(TAG, "No stale entry; trying next suffix")
                            suffix++
                            continue
                        }
                    }
                    // Other errors are not name conflicts; give up
                    val preview = previewDestinationPath(src, candidate)
                    Log.e(TAG, "Insert failed for $preview: ${e.message}", e)
                    throw IllegalStateException("Insert failed for $preview: ${e.message}")
                }
            }
            val preview = previewDestinationPath(src, "$base($suffix).$ext")
            throw IllegalStateException("Unable to find unique name after $suffix attempts for $preview")
        } catch (_: Exception) {
            null
        }
    }

    private fun tryCleanupStaleEntry(
        cr: android.content.ContentResolver,
        collection: Uri,
        relativePath: String?,
        displayName: String
    ): Boolean {
        return try {
            val proj = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_ADDED
            )
            val sel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?"
            } else {
                "${MediaStore.MediaColumns.DISPLAY_NAME}=?"
            }
            val args = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) arrayOf(relativePath ?: "", displayName) else arrayOf(displayName)
            cr.query(collection, proj, sel, args, null)?.use { c ->
                if (!c.moveToFirst()) {
                    Log.d(TAG, "No existing MediaStore row for $displayName in relPath=$relativePath")
                    return false
                }
                val idIdx = c.getColumnIndex(MediaStore.MediaColumns._ID)
                val sizeIdx = c.getColumnIndex(MediaStore.MediaColumns.SIZE)
                val dateIdx = c.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
                val id = if (idIdx >= 0) c.getLong(idIdx) else -1L
                val size = if (sizeIdx >= 0) c.getLong(sizeIdx) else -1L
                val dateAdded = if (dateIdx >= 0) c.getLong(dateIdx) else -1L
                Log.d(TAG, "Existing row for $displayName -> id=$id size=$size dateAdded=$dateAdded")
                if (id < 0) return false

                val itemUri = ContentUris.withAppendedId(collection, id)
                // If size <= 0 OR cannot open for read, treat as stale and delete
                val canOpen = runCatching { cr.openFileDescriptor(itemUri, "r")?.use { it.statSize >= 0 } ?: false }.getOrElse { false }
                if (size <= 0L || !canOpen) {
                    val deleted = runCatching { cr.delete(itemUri, null, null) > 0 }.getOrElse { false }
                    Log.d(TAG, "Cleanup stale/dangling entry id=$id size=$size canOpen=$canOpen deleted=$deleted")
                    return deleted
                }
                // Not stale; keep
                return false
            } ?: false
        } catch (e: Exception) {
            Log.d(TAG, "tryCleanupStaleEntry error: ${e.message}")
            false
        }
    }

    private fun deriveRelativePathFromDocumentUri(uri: Uri): String? {
        return try {
            // Works for com.android.externalstorage.documents and media documents
            val isDoc = android.provider.DocumentsContract.isDocumentUri(context, uri)
            if (!isDoc) return null
            val docId = android.provider.DocumentsContract.getDocumentId(uri)
            // If this is an externalstorage document with a volume prefix, honor it
            // Examples:
            //  externalstorage:  "primary:DCIM/Camera/IMG_0001.PNG"
            //  externalstorage:  "raw:/storage/emulated/0/Download/file.png"
            //  downloads:       "raw:/storage/emulated/0/Download/file.png" or other forms
            //  media:            "image:3952" (no path info)
            val colon = docId.indexOf(':')
            val pathPart = if (colon > 0) docId.substring(colon + 1) else docId
            if (pathPart.isEmpty()) return null

            // Normalize potential absolute path to RELATIVE_PATH under external storage root
            var normalized = pathPart
            if (normalized.startsWith("raw:")) normalized = normalized.removePrefix("raw:")
            if (normalized.startsWith("/")) {
                // Strip any /storage/<volume>/ prefix to make it RELATIVE_PATH
                val parts = normalized.removePrefix("/storage/")
                val idx = parts.indexOf('/')
                if (idx > 0) {
                    normalized = parts.substring(idx + 1)
                } else return null
            }

            val lastSlash = normalized.lastIndexOf('/')
            if (lastSlash < 0) return null
            var dir = normalized.substring(0, lastSlash + 1) // keep trailing slash
            if (!dir.endsWith("/")) dir += "/"
            dir
        } catch (_: Exception) {
            null
        }
    }

    private fun createFileSiblingHandle(src: Uri, newName: String): WriteHandle? {
        return try {
            val path = src.path ?: return null
            val parent = File(path).parentFile ?: return null
            if (!parent.exists()) parent.mkdirs()
            var outFile = File(parent, newName)
            var counter = 1
            while (outFile.exists()) {
                val base = newName.substringBeforeLast('.')
                val ext = newName.substringAfterLast('.', "jpg")
                outFile = File(parent, "$base($counter).$ext")
                counter++
            }
            val os = outFile.outputStream()
            WriteHandle(output = os, destFile = outFile)
        } catch (_: Exception) {
            null
        }
    }

    private fun createFileSiblingHandleFromDir(absDir: String, newName: String): WriteHandle? {
        return try {
            val parent = File(absDir)
            if (!parent.exists()) parent.mkdirs()
            var outFile = File(parent, newName)
            var counter = 1
            while (outFile.exists()) {
                val base = newName.substringBeforeLast('.')
                val ext = newName.substringAfterLast('.', "jpg")
                outFile = File(parent, "$base($counter).$ext")
                counter++
            }
            val os = outFile.outputStream()
            WriteHandle(output = os, destFile = outFile)
        } catch (e: Exception) {
            Log.e(TAG, "createFileSiblingHandleFromDir error for dir=$absDir name=$newName: ${e.message}")
            null
        }
    }

    private fun resolveAbsoluteTargetDir(src: Uri): String? {
        return try {
            when (src.scheme?.lowercase()) {
                "file" -> File(src.path ?: return null).parent
                "content" -> {
                    val isDoc = android.provider.DocumentsContract.isDocumentUri(context, src)
                    if (isDoc) {
                        val docId = android.provider.DocumentsContract.getDocumentId(src)
                        var pathPart = docId
                        val colon = docId.indexOf(':')
                        if (colon > 0) pathPart = docId.substring(colon + 1)
                        if (pathPart.startsWith("raw:")) pathPart = pathPart.removePrefix("raw:")
                        // When we have an absolute path, allow any /storage/<volume>/ path
                        if (pathPart.startsWith("/")) {
                            // Accept paths like /storage/emulated/0/..., /storage/XXXX-XXXX/...
                            return File(pathPart).parent
                        }
                        // If it looks like a primary-relative (e.g., "Download/.."), prefix the appropriate root
                        if (pathPart.contains('/')) {
                            val vol = resolveVolumeName(src)
                            val root = if (vol != null && !vol.equals(MediaStore.VOLUME_EXTERNAL_PRIMARY, true)) {
                                "/storage/" + vol + "/"
                            } else Environment.getExternalStorageDirectory().absolutePath.trimEnd('/') + "/"
                            val dir = pathPart.substring(0, pathPart.lastIndexOf('/') + 1)
                            return root + dir
                        }
                        null
                    } else {
                        // Heuristic by authority/path
                        val auth = src.authority?.lowercase(Locale.US)
                        val path = src.path?.lowercase(Locale.US)
                        if (auth?.contains("downloads") == true || (path?.contains("/download/") == true)) {
                            val vol = resolveVolumeName(src)
                            if (vol != null && !vol.equals(MediaStore.VOLUME_EXTERNAL_PRIMARY, true)) {
                                "/storage/" + vol + "/Download/"
                            } else Environment.getExternalStorageDirectory().absolutePath.trimEnd('/') + "/Download/"
                        } else null
                    }
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    

    private fun destinationExists(handle: WriteHandle): Boolean {
        handle.destFile?.let { return it.exists() && it.length() > 0 }
        handle.destUri?.let { dest ->
            return try {
                context.contentResolver.openFileDescriptor(dest, "r")?.use { it.statSize >= 0 } ?: false
            } catch (_: Exception) {
                false
            }
        }
        return false
    }

    private fun deleteSource(src: Uri): Boolean {
        return try {
            when (src.scheme?.lowercase()) {
                "file" -> {
                    val path = src.path ?: return false
                    val f = File(path)
                    f.exists() && f.delete()
                }
                "content" -> {
                    // Try SAF delete via DocumentFile first (works for many DocumentsProviders on Android 10)
                    val doc = try { DocumentFile.fromSingleUri(context, src) } catch (_: Exception) { null }
                    if (doc != null) {
                        val ok = runCatching { doc.delete() }.getOrNull() == true
                        if (ok) return true
                    }
                    val cr = context.contentResolver
                    val rows = try { cr.delete(src, null, null) } catch (_: SecurityException) { -1 }
                    rows > 0
                }
                else -> false
            }
        } catch (_: Exception) {
            false
        }
    }
}

package com.example.exifdateeditor

import android.content.Context
import android.content.ContentValues
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.DocumentsContract
import android.media.MediaScannerConnection
import android.content.ContentUris
import android.os.Build
import java.io.FileNotFoundException
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.*
import android.util.Log
import android.app.RecoverableSecurityException
import com.coremedia.iso.IsoFile
import com.coremedia.iso.boxes.MovieHeaderBox
import com.coremedia.iso.boxes.TrackHeaderBox
import com.coremedia.iso.boxes.MediaHeaderBox
import com.googlecode.mp4parser.util.Path

/**
 * Manages EXIF/media metadata reading and writing
 */
object ExifManager {
    
    private const val TAG = "ExifManager"
    private const val EXIF_DATE_FORMAT = "yyyy:MM:dd HH:mm:ss"
    private val dateFormatter = SimpleDateFormat(EXIF_DATE_FORMAT, Locale.US)
    private val offsetFormatter = SimpleDateFormat("XXX", Locale.US)
    
    /**
     * Read EXIF DateTimeOriginal (date taken) from image
     * Returns null if not set or cannot be read
     */
    fun getDateTaken(context: Context, imageUri: Uri): Date? {
        return try {
            val inputStream: InputStream? = context.contentResolver.openInputStream(imageUri)
            inputStream?.use {
                val exifInterface = ExifInterface(it)
                val dateTimeOriginal = exifInterface.getAttribute(
                    ExifInterface.TAG_DATETIME_ORIGINAL
                )
                
                if (dateTimeOriginal != null) {
                    dateFormatter.parse(dateTimeOriginal)
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Get formatted string of DateTimeOriginal
     * Returns "Not set" if no date is available
     */
    fun getDateTakenFormatted(context: Context, imageUri: Uri): String {
        val date = getMediaDateTaken(context, imageUri)
        return if (date != null) {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(date)
        } else {
            "Not set"
        }
    }
    
    /**
     * Check if image has DateTimeOriginal tag set
     */
    fun hasDateTaken(context: Context, imageUri: Uri): Boolean {
        return getDateTaken(context, imageUri) != null
    }
    
    /**
     * Set EXIF DateTimeOriginal (date taken) for image
     * Also sets DateTime and DateTimeDigitized for consistency
     * Also updates the file's modification date and Media Store for correct sorting
     * Returns true if successful, false otherwise
     */
    fun setDateTaken(context: Context, imageUri: Uri, date: Date): Boolean {
        return try {
            val dateString = dateFormatter.format(date)
            val offsetString = offsetFormatter.format(date)
            var success = false
            
            // Prefer direct update via a read-write file descriptor when possible
            try {
                val pfd: ParcelFileDescriptor? =
                    context.contentResolver.openFileDescriptor(imageUri, "rw")
                if (pfd != null) {
                    pfd.use { fd ->
                        val exifInterface = ExifInterface(fd.fileDescriptor)
                        exifInterface.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, dateString)
                        exifInterface.setAttribute(ExifInterface.TAG_DATETIME, dateString)
                        exifInterface.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, dateString)
                        exifInterface.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, offsetString)
                        exifInterface.setAttribute(ExifInterface.TAG_OFFSET_TIME, offsetString)
                        exifInterface.setAttribute(ExifInterface.TAG_OFFSET_TIME_DIGITIZED, offsetString)
                        exifInterface.saveAttributes()
                        success = true
                    }
                }
            } catch (se: SecurityException) {
                // Lacks write permission to the content Uri; fall back below
            } catch (e: Exception) {
                // Fall back to temp-file strategy
            }

            if (!success) {
                // Fallback: temp-file copy, then overwrite via output stream (requires write access)
                val tempFile = File.createTempFile("exif_temp", ".jpg", context.cacheDir)
                try {
                    context.contentResolver.openInputStream(imageUri)?.use { input ->
                        tempFile.outputStream().use { output -> input.copyTo(output) }
                    }

                    val exifInterface = ExifInterface(tempFile.absolutePath)
                    exifInterface.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, dateString)
                    exifInterface.setAttribute(ExifInterface.TAG_DATETIME, dateString)
                    exifInterface.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, dateString)
                    exifInterface.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, offsetString)
                    exifInterface.setAttribute(ExifInterface.TAG_OFFSET_TIME, offsetString)
                    exifInterface.setAttribute(ExifInterface.TAG_OFFSET_TIME_DIGITIZED, offsetString)
                    exifInterface.saveAttributes()

                    context.contentResolver.openOutputStream(imageUri)?.use { output ->
                        tempFile.inputStream().use { input -> input.copyTo(output) }
                    } ?: return false

                    success = true
                } finally {
                    tempFile.delete()
                }
            }

            if (success) {
                // Update file modification date and Media Store for correct sorting
                updateFileModificationDate(context, imageUri, date)
                notifyMediaStore(context, imageUri)
            }

            success
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Update the file's modification date to match the EXIF date taken
     * This ensures the file system timestamp is synchronized with the EXIF date
     */
    private fun updateFileModificationDate(context: Context, imageUri: Uri, date: Date) {
        try {
            // Try to get the actual file path from the URI
            val filePath = getFilePathFromUri(context, imageUri)
            if (filePath != null) {
                val file = File(filePath)
                if (file.exists()) {
                    file.setLastModified(date.time)
                    Log.d(TAG, "Updated file modification date for $filePath to ${dateFormatter.format(date)}")
                }
            }
            // Note: File system modification may be overwritten by the system when writing
            // The important part is updating MediaStore.DATE_MODIFIED which is done in notifyMediaStore()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update file modification date: ${e.message}")
        }
    }

    /**
     * Notify MediaStore about file changes so it re-indexes the file with the new timestamp
     * This updates Android's media database for correct sorting in image viewers
     */
    private fun notifyMediaStore(context: Context, imageUri: Uri) {
        try {
            // Update MediaStore timestamps to match the EXIF date
            val currentDate = getDateTaken(context, imageUri)
            if (currentDate != null) {
                val contentValues = ContentValues().apply {
                    // DATE_TAKEN is stored as milliseconds since epoch
                    put(MediaStore.Images.Media.DATE_TAKEN, currentDate.time)
                    // DATE_MODIFIED is stored as seconds since epoch in MediaStore
                    put(MediaStore.MediaColumns.DATE_MODIFIED, currentDate.time / 1000)
                }
                
                val updated = context.contentResolver.update(imageUri, contentValues, null, null)
                Log.d(TAG, "Updated MediaStore DATE_MODIFIED: $updated rows affected")
            }
            
            // Also notify content resolver to clear any caches
            context.contentResolver.notifyChange(imageUri, null)
            // Force a rescan so MediaStore re-reads EXIF and updates DATE_TAKEN
            val scanPath = getFilePathFromUri(context, imageUri)
            if (scanPath != null) {
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(scanPath),
                    null
                ) { _, _ -> }
            }
            Log.d(TAG, "Notified MediaStore about file change")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to notify MediaStore: ${e.message}")
        }
    }

    private fun notifyMediaStoreWithDate(context: Context, mediaUri: Uri, date: Date) {
        try {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DATE_MODIFIED, date.time / 1000)
                put(MediaStore.MediaColumns.DATE_TAKEN, date.time)
            }
            context.contentResolver.update(mediaUri, contentValues, null, null)
            context.contentResolver.notifyChange(mediaUri, null)
            val scanPath = getFilePathFromUri(context, mediaUri)
            if (scanPath != null) {
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(scanPath),
                    null
                ) { _, _ -> }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to notify MediaStore for media: ${e.message}")
        }
    }

    /**
     * Set date taken for media. Images update EXIF + MediaStore, videos update MediaStore timestamps.
     */
    fun setDateTakenForMedia(context: Context, mediaUri: Uri, date: Date): Boolean {
        val mimeType = try { context.contentResolver.getType(mediaUri) } catch (_: Exception) { null }
        return if (mimeType != null && mimeType.startsWith("video/")) {
            setVideoDateTaken(context, mediaUri, date)
        } else {
            setDateTaken(context, mediaUri, date)
        }
    }

    private fun setVideoDateTaken(context: Context, videoUri: Uri, date: Date): Boolean {
        return try {
            val targetUri = resolveVideoMediaStoreUri(context, videoUri)
            if (targetUri == null) {
                Log.w(TAG, "Video update skipped: could not resolve MediaStore URI for $videoUri")
                return false
            }
            Log.d(TAG, "Resolved video MediaStore URI: $targetUri")
            updateFileModificationDate(context, targetUri, date)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DATE_MODIFIED, date.time / 1000)
                put(MediaStore.Video.Media.DATE_TAKEN, date.time)
            }
            val updated = context.contentResolver.update(targetUri, values, null, null)
            Log.d(TAG, "Updated video rows: $updated for $targetUri")
            notifyMediaStoreWithDate(context, targetUri, date)
            if (updated > 0) {
                true
            } else {
                val mp4Updated = tryUpdateMp4Metadata(context, videoUri, date)
                if (mp4Updated) {
                    val scanPath = getFilePathFromUri(context, targetUri) ?: getFilePathFromUri(context, videoUri)
                    if (scanPath != null) {
                        MediaScannerConnection.scanFile(context, arrayOf(scanPath), null) { _, _ -> }
                    }
                }
                mp4Updated
            }
        } catch (e: RecoverableSecurityException) {
            val intentSender = e.userAction.actionIntent.intentSender
            throw RecoverableWriteException(intentSender, videoUri)
        } catch (e: UnsupportedOperationException) {
            Log.w(TAG, "Video provider does not support update: ${e.message}")
            false
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update video timestamps: ${e.message}")
            false
        }
    }

    private fun tryUpdateMp4Metadata(context: Context, videoUri: Uri, date: Date): Boolean {
        // Update MP4 creation/modification time so MediaStore can reindex DATE_TAKEN.
        return try {
            val tempIn = File.createTempFile("video_in", ".mp4", context.cacheDir)
            val tempOut = File.createTempFile("video_out", ".mp4", context.cacheDir)

            context.contentResolver.openInputStream(videoUri)?.use { input ->
                tempIn.outputStream().use { output -> input.copyTo(output) }
            } ?: return false

            val isoFile = IsoFile(tempIn.absolutePath)
            try {
                val mvhd = Path.getPath(isoFile, "moov/mvhd") as? MovieHeaderBox
                mvhd?.setCreationTime(date)
                mvhd?.setModificationTime(date)

                val tkhdBoxes: List<TrackHeaderBox> = Path.getPaths(isoFile, "moov/trak/tkhd")
                for (tkhd in tkhdBoxes) {
                    tkhd.setCreationTime(date)
                    tkhd.setModificationTime(date)
                }

                val mdhdBoxes: List<MediaHeaderBox> = Path.getPaths(isoFile, "moov/trak/mdia/mdhd")
                for (mdhd in mdhdBoxes) {
                    mdhd.setCreationTime(date)
                    mdhd.setModificationTime(date)
                }

                FileOutputStream(tempOut).channel.use { channel ->
                    isoFile.getBox(channel)
                }
            } finally {
                isoFile.close()
            }

            context.contentResolver.openOutputStream(videoUri, "rwt")?.use { output ->
                tempOut.inputStream().use { input -> input.copyTo(output) }
            } ?: return false

            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update MP4 metadata: ${e.message}")
            false
        }
    }

    class RecoverableWriteException(
        val intentSender: android.content.IntentSender,
        val uri: Uri
    ) : Exception("Recoverable write permission required for $uri")

    private fun resolveVideoMediaStoreUri(context: Context, videoUri: Uri): Uri? {
        if (videoUri.scheme != "content") return null
        if (videoUri.authority == MediaStore.AUTHORITY) return videoUri
        var volumeName: String? = null
        var name: String? = null
        var relDir: String? = null
        var pathPart: String? = null

        var treePathPart: String? = null
        if (DocumentsContract.isDocumentUri(context, videoUri)) {
            val docId = try { DocumentsContract.getDocumentId(videoUri) } catch (_: Exception) { null }
            if (!docId.isNullOrEmpty()) {
                val parts = docId.split(":")
                if (parts.size == 2) {
                    volumeName = parts[0]
                    pathPart = parts[1]
                    name = pathPart.substringAfterLast('/', pathPart)
                    relDir = pathPart.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
                }
            }
        }
        if (pathPart.isNullOrEmpty()) {
            val treeId = try { DocumentsContract.getTreeDocumentId(videoUri) } catch (_: Exception) { null }
            if (!treeId.isNullOrEmpty()) {
                val parts = treeId.split(":")
                if (parts.size == 2) {
                    volumeName = volumeName ?: parts[0]
                    treePathPart = parts[1]
                }
            }
        }

        // Fallback: try display name from the URI if available
        if (name.isNullOrEmpty()) {
            name = getDisplayName(context, videoUri)
        }

        if (name.isNullOrEmpty()) {
            Log.w(TAG, "resolveVideoMediaStoreUri: missing display name for $videoUri")
            return null
        }

        val volume = when {
            volumeName.isNullOrEmpty() -> MediaStore.VOLUME_EXTERNAL
            volumeName.equals("primary", true) -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    MediaStore.VOLUME_EXTERNAL_PRIMARY
                } else {
                    MediaStore.VOLUME_EXTERNAL
                }
            }
            else -> volumeName
        }
        Log.d(TAG, "resolveVideoMediaStoreUri: name=$name relDir=$relDir volume=$volume for $videoUri")

        val dataPath = buildDataPath(volumeName, pathPart ?: treePathPart)
        val volumesToTry = LinkedHashSet<String>().apply {
            add(volume)
            add(MediaStore.VOLUME_EXTERNAL)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                add(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }
            if (!volumeName.isNullOrEmpty() && !volumeName.equals("primary", true)) {
                add(volumeName!!)
            }
        }.toList()

        for (vol in volumesToTry) {
            val resolved = queryVideoByNameOrPath(context, vol, name, relDir, dataPath)
            if (resolved != null) return resolved
        }

        return null
    }

    private fun buildDataPath(volumeName: String?, pathPart: String?): String? {
        if (pathPart.isNullOrEmpty()) return null
        return if (volumeName.isNullOrEmpty() || volumeName.equals("primary", true)) {
            "/storage/emulated/0/$pathPart"
        } else {
            "/storage/$volumeName/$pathPart"
        }
    }

    private fun queryVideoByNameOrPath(
        context: Context,
        volume: String,
        name: String,
        relDir: String?,
        dataPath: String?
    ): Uri? {
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val videoCollection = try {
            MediaStore.Video.Media.getContentUri(volume)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Invalid MediaStore volume: $volume")
            return null
        }
        val filesCollection = try {
            MediaStore.Files.getContentUri(volume)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Invalid MediaStore volume for files: $volume")
            return null
        }

        if (!relDir.isNullOrEmpty()) {
            val selection = "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?"
            val selectionArgs = arrayOf(name, relDir)
            try {
                context.contentResolver.query(videoCollection, projection, selection, selectionArgs, null)
            } catch (_: IllegalArgumentException) {
                null
            }?.use { cursor ->
                val idIdx = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
                if (cursor.moveToFirst() && idIdx >= 0) {
                    val id = cursor.getLong(idIdx)
                    return ContentUris.withAppendedId(videoCollection, id)
                }
            }
        }

        if (!dataPath.isNullOrEmpty()) {
            val selection = "${MediaStore.MediaColumns.DATA}=?"
            val selectionArgs = arrayOf(dataPath)
            try {
                context.contentResolver.query(videoCollection, projection, selection, selectionArgs, null)
            } catch (_: IllegalArgumentException) {
                null
            }?.use { cursor ->
                val idIdx = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
                if (cursor.moveToFirst() && idIdx >= 0) {
                    val id = cursor.getLong(idIdx)
                    return ContentUris.withAppendedId(videoCollection, id)
                }
            }
        }

        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME}=?"
        val selectionArgs = arrayOf(name)
        try {
            context.contentResolver.query(videoCollection, projection, selection, selectionArgs, null)
        } catch (_: IllegalArgumentException) {
            null
        }?.use { cursor ->
            val idIdx = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
            if (cursor.moveToFirst() && idIdx >= 0) {
                val id = cursor.getLong(idIdx)
                if (!cursor.moveToNext()) {
                    return ContentUris.withAppendedId(videoCollection, id)
                }
            }
        }

        // Files collection fallback if Video collection doesn't match
        if (!relDir.isNullOrEmpty()) {
            val filesSelection = "${MediaStore.Files.FileColumns.MEDIA_TYPE}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?"
            val filesSelectionArgs = arrayOf(
                MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
                name,
                relDir
            )
            try {
                context.contentResolver.query(filesCollection, projection, filesSelection, filesSelectionArgs, null)
            } catch (_: IllegalArgumentException) {
                null
            }?.use { cursor ->
                val idIdx = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
                if (cursor.moveToFirst() && idIdx >= 0) {
                    val id = cursor.getLong(idIdx)
                    return ContentUris.withAppendedId(videoCollection, id)
                }
            }
        }

        if (!dataPath.isNullOrEmpty()) {
            val filesSelection = "${MediaStore.MediaColumns.DATA}=?"
            val filesSelectionArgs = arrayOf(dataPath)
            try {
                context.contentResolver.query(filesCollection, projection, filesSelection, filesSelectionArgs, null)
            } catch (_: IllegalArgumentException) {
                null
            }?.use { cursor ->
                val idIdx = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
                if (cursor.moveToFirst() && idIdx >= 0) {
                    val id = cursor.getLong(idIdx)
                    return ContentUris.withAppendedId(videoCollection, id)
                }
            }
        }

        val filesSelection = "${MediaStore.Files.FileColumns.MEDIA_TYPE}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?"
        val filesSelectionArgs = arrayOf(MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(), name)
        try {
            context.contentResolver.query(filesCollection, projection, filesSelection, filesSelectionArgs, null)
        } catch (_: IllegalArgumentException) {
            null
        }?.use { cursor ->
            val idIdx = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
            if (cursor.moveToFirst() && idIdx >= 0) {
                val id = cursor.getLong(idIdx)
                if (!cursor.moveToNext()) {
                    return ContentUris.withAppendedId(videoCollection, id)
                }
            }
        }

        return null
    }

    /**
     * Get date taken for any media (image/video).
     * For images, prefer EXIF. Otherwise fall back to MediaStore DATE_TAKEN.
     */
    fun getMediaDateTaken(context: Context, mediaUri: Uri): Date? {
        val exifDate = getDateTaken(context, mediaUri)
        if (exifDate != null) return exifDate
        return try {
            val projection = arrayOf(MediaStore.MediaColumns.DATE_TAKEN)
            context.contentResolver.query(mediaUri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_TAKEN)
                    if (idx >= 0) {
                        val millis = cursor.getLong(idx)
                        if (millis > 0) Date(millis) else null
                    } else null
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Get the actual file path from a URI when possible
     */
    private fun getFilePathFromUri(context: Context, uri: Uri): String? {
        return try {
            when (uri.scheme) {
                "file" -> uri.path
                "content" -> {
                    // Try to get the path from MediaStore
                    val projection = arrayOf(MediaStore.MediaColumns.DATA)
                    context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val column = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                            cursor.getString(column)
                        } else null
                    }
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not extract file path from URI: ${e.message}")
            null
        }
    }
    
    /**
     * Get all EXIF metadata for image (for debugging/display)
     */
    fun getAllExifData(context: Context, imageUri: Uri): Map<String, String> {
        val result = mutableMapOf<String, String>()
        return try {
            val inputStream: InputStream? = context.contentResolver.openInputStream(imageUri)
            inputStream?.use {
                val exifInterface = ExifInterface(it)
                
                // Get commonly used tags
                val tags = arrayOf(
                    ExifInterface.TAG_DATETIME_ORIGINAL,
                    ExifInterface.TAG_DATETIME,
                    ExifInterface.TAG_DATETIME_DIGITIZED,
                    ExifInterface.TAG_MAKE,
                    ExifInterface.TAG_MODEL,
                    ExifInterface.TAG_IMAGE_WIDTH,
                    ExifInterface.TAG_IMAGE_LENGTH
                )
                
                for (tag in tags) {
                    val value = exifInterface.getAttribute(tag)
                    if (value != null) {
                        result[tag] = value
                    }
                }
            }
            result
        } catch (e: Exception) {
            result
        }
    }

    /**
     * Find MediaStore entries for the selected filenames that no longer resolve to a file.
     * Returns a list of stale MediaStore item URIs.
     */
    fun findStaleMediaEntries(context: Context, imageUris: List<Uri>): List<Uri> {
        val displayNames = imageUris.mapNotNull { getDisplayName(context, it) }.toSet()
        if (displayNames.isEmpty()) return emptyList()

        val stale = mutableListOf<Uri>()
        val collections = listOf(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        )
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME
        )

        for (collection in collections) {
            for (name in displayNames) {
                val selection = "${MediaStore.MediaColumns.DISPLAY_NAME}=?"
                val selectionArgs = arrayOf(name)
                context.contentResolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
                    val idIdx = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idIdx)
                        val itemUri = ContentUris.withAppendedId(collection, id)
                        try {
                            context.contentResolver.openInputStream(itemUri)?.use { }
                        } catch (_: FileNotFoundException) {
                            stale.add(itemUri)
                        } catch (_: SecurityException) {
                            stale.add(itemUri)
                        } catch (_: Exception) {
                            // Ignore other errors
                        }
                    }
                }
            }
        }

        return stale
    }

    private fun getDisplayName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) cursor.getString(nameIndex) else null
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }
}

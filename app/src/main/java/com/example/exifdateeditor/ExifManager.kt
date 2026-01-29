package com.example.exifdateeditor

import android.content.Context
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.media.MediaScannerConnection
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.*
import android.util.Log

/**
 * Manages EXIF data reading and writing for images
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
        val date = getDateTaken(context, imageUri)
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
}

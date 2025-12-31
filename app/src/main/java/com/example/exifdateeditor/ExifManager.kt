package com.example.exifdateeditor

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.*

/**
 * Manages EXIF data reading and writing for images
 */
object ExifManager {
    
    private const val EXIF_DATE_FORMAT = "yyyy:MM:dd HH:mm:ss"
    private val dateFormatter = SimpleDateFormat(EXIF_DATE_FORMAT, Locale.US)
    
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
     * Returns true if successful, false otherwise
     */
    fun setDateTaken(context: Context, imageUri: Uri, date: Date): Boolean {
        return try {
            val dateString = dateFormatter.format(date)
            
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
                        exifInterface.saveAttributes()
                        return true
                    }
                }
            } catch (se: SecurityException) {
                // Lacks write permission to the content Uri; fall back below
            } catch (e: Exception) {
                // Fall back to temp-file strategy
            }

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
                exifInterface.saveAttributes()

                context.contentResolver.openOutputStream(imageUri)?.use { output ->
                    tempFile.inputStream().use { input -> input.copyTo(output) }
                } ?: return false

                true
            } finally {
                tempFile.delete()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
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

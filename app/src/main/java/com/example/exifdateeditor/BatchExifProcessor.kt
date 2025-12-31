package com.example.exifdateeditor

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.*
import java.util.*

/**
 * Handles batch EXIF operations on multiple images
 */
class BatchExifProcessor(
    private val context: Context,
    private val uris: List<Uri>
) {
    
    var onProgress: (current: Int, total: Int, fileName: String) -> Unit = { _, _, _ -> }
    var onComplete: (result: BatchOperationResult) -> Unit = {}
    
    /**
     * Set EXIF DateTimeOriginal for all images
     * Runs on IO thread to avoid blocking UI
     */
    fun setDateTakenForAll(date: Date) {
        CoroutineScope(Dispatchers.IO + Job()).launch {
            val results = mutableListOf<Pair<String, Boolean>>()
            val errorMessages = mutableMapOf<String, String>()
            
            for ((index, uri) in uris.withIndex()) {
                try {
                    val fileName = getFileName(uri)
                    
                    // Update UI on main thread
                    withContext(Dispatchers.Main) {
                        onProgress(index + 1, uris.size, fileName)
                    }
                    
                    // Perform EXIF write
                    val success = ExifManager.setDateTaken(context, uri, date)
                    results.add(fileName to success)
                    
                    if (!success) {
                        errorMessages[fileName] = "Failed to write EXIF data"
                    }
                } catch (e: Exception) {
                    val fileName = getFileName(uri)
                    results.add(fileName to false)
                    errorMessages[fileName] = e.message ?: "Unknown error"
                }
            }
            
            // Calculate results
            val successCount = results.count { it.second }
            val failureCount = results.count { !it.second }
            val failedImages = results.filter { !it.second }.map { it.first }
            
            val result = BatchOperationResult(
                successCount = successCount,
                failureCount = failureCount,
                failedImages = failedImages,
                errorMessages = errorMessages
            )
            
            withContext(Dispatchers.Main) {
                onComplete(result)
            }
        }
    }
    
    private fun getFileName(uri: Uri): String {
        return try {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(android.provider.MediaStore.MediaColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) {
                        return@use it.getString(nameIndex)
                    } else {
                        null
                    }
                } else {
                    null
                }
            } ?: (uri.lastPathSegment ?: "Unknown")
        } catch (e: Exception) {
            uri.lastPathSegment ?: "Unknown"
        }
    }
}

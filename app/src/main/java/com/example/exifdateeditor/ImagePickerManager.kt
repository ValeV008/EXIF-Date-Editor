package com.example.exifdateeditor

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * Manages image selection using Storage Access Framework (SAF)
 */
class ImagePickerManager(
    private val context: Context,
    registry: ActivityResultRegistry,
    lifecycleOwner: LifecycleOwner
) : DefaultLifecycleObserver {
    
    var onImagesSelected: (List<Uri>) -> Unit = {}
    
    private val selectMultipleImages = registry.register(
        "select_multiple_images",
        lifecycleOwner,
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        // Persist read/write access where possible (SAF)
        val filtered = uris.filter { isImageFile(it) }
        filtered.forEach { uri ->
            try {
                val takeFlags =
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, takeFlags)
            } catch (_: Exception) {
                // Ignore; some providers won't grant write persist here
            }
        }
        onImagesSelected(filtered)
    }
    
    private val selectSingleImage = registry.register(
        "select_single_image",
        lifecycleOwner,
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null && isImageFile(uri)) {
            try {
                val takeFlags =
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, takeFlags)
            } catch (_: Exception) {
                // Ignore
            }
            onImagesSelected(listOf(uri))
        }
    }
    
    /**
     * Open picker for multiple images
     */
    fun pickMultipleImages() {
        selectMultipleImages.launch(arrayOf("image/*"))
    }
    
    /**
     * Open picker for single image
     */
    fun pickSingleImage() {
        selectSingleImage.launch(arrayOf("image/*"))
    }
    
    /**
     * Check if URI points to a valid image file
     */
    private fun isImageFile(uri: Uri): Boolean {
        return try {
            val mimeType = context.contentResolver.getType(uri)
            mimeType?.startsWith("image/") ?: false
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Get display name for image URI
     */
    fun getImageName(uri: Uri): String {
        return try {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
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
    
    /**
     * Get file size for image URI
     */
    fun getImageSize(uri: Uri): Long {
        return try {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val sizeIndex = it.getColumnIndex(MediaStore.MediaColumns.SIZE)
                    if (sizeIndex >= 0) {
                        return@use it.getLong(sizeIndex)
                    } else {
                        null
                    }
                } else {
                    null
                }
            } ?: 0L
        } catch (e: Exception) {
            0L
        }
    }
}

package com.example.exifdateeditor

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import java.util.Locale

/**
 * Manages image selection using Storage Access Framework (SAF)
 */
class ImagePickerManager(
    private val context: Context,
    registry: ActivityResultRegistry,
    lifecycleOwner: LifecycleOwner
) : DefaultLifecycleObserver {
    
    var onImagesSelected: (List<Uri>) -> Unit = {}
    private var pendingCallback: ((List<Uri>) -> Unit)? = null
    private var pendingMimeTypesFilter: Array<String>? = null
    
    private val selectMultipleImages = registry.register(
        "select_multiple_images",
        lifecycleOwner,
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        // Persist read/write access where possible (SAF)
        val accepted = pendingMimeTypesFilter ?: arrayOf("image/*")
        val filtered = uris.filter { uri ->
            try {
                val mimeType = context.contentResolver.getType(uri)
                matchesMimeTypes(mimeType, accepted, uri)
            } catch (_: Exception) {
                false
            }
        }
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
        val cb = pendingCallback
        pendingCallback = null
        pendingMimeTypesFilter = null
        if (cb != null) {
            cb.invoke(filtered)
        } else {
            onImagesSelected(filtered)
        }
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
        pendingMimeTypesFilter = arrayOf("image/*")
        selectMultipleImages.launch(arrayOf("image/*"))
    }
    
    /**
     * Open picker for multiple images with custom MIME type filters and optional one-shot callback
     */
    fun pickMultipleImages(mimeTypes: Array<String>, callback: ((List<Uri>) -> Unit)? = null) {
        pendingCallback = callback
        pendingMimeTypesFilter = mimeTypes
        selectMultipleImages.launch(mimeTypes)
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

    private fun matchesMimeTypes(mimeType: String?, accepted: Array<String>, uri: Uri): Boolean {
        if (!mimeType.isNullOrEmpty()) {
            return accepted.any { pattern ->
                val p = pattern.lowercase(Locale.ROOT)
                val m = mimeType.lowercase(Locale.ROOT)
                if (p.endsWith("/*")) {
                    // Wildcard major type match, e.g., image/*
                    val major = p.substringBefore('/')
                    m.substringBefore('/') == major
                } else {
                    m == p
                }
            }
        }

        // Fallback for providers that don't return a MIME type.
        val name = getImageName(uri)
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (ext.isEmpty()) return false

        val imageExts = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "bmp", "tif", "tiff", "dng")
        val videoExts = setOf("mp4", "m4v", "mov", "mkv", "avi", "webm", "3gp", "3gpp", "3g2", "ts", "m2ts", "mts")

        fun matchesSubtype(subtype: String): Boolean {
            return when (subtype) {
                "jpeg", "jpg" -> ext == "jpg" || ext == "jpeg"
                "quicktime" -> ext == "mov"
                else -> ext == subtype
            }
        }

        return accepted.any { pattern ->
            val p = pattern.lowercase(Locale.ROOT)
            if (p.endsWith("/*")) {
                val major = p.substringBefore('/')
                when (major) {
                    "image" -> ext in imageExts
                    "video" -> ext in videoExts
                    else -> false
                }
            } else {
                val subtype = p.substringAfter('/', "")
                matchesSubtype(subtype)
            }
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

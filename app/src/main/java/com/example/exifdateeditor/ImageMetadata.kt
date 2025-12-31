package com.example.exifdateeditor

import android.net.Uri
import java.util.*

/**
 * Data class representing an image with its metadata
 */
data class ImageMetadata(
    val uri: Uri,
    val name: String,
    val size: Long,
    val currentDateTaken: Date?,
    val hasDateTaken: Boolean
)

/**
 * Data class for batch operation results
 */
data class BatchOperationResult(
    val successCount: Int,
    val failureCount: Int,
    val failedImages: List<String>,
    val errorMessages: Map<String, String>
)

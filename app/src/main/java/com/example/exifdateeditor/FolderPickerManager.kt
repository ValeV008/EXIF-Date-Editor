package com.example.exifdateeditor

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import java.io.File

/**
 * Manages folder selection and image extraction using Storage Access Framework (SAF)
 * Persists folder access permissions so user doesn't get prompted repeatedly
 */
class FolderPickerManager(
    private val context: Context,
    registry: ActivityResultRegistry,
    lifecycleOwner: LifecycleOwner
) : DefaultLifecycleObserver {
    
    var onImagesFound: (List<Uri>) -> Unit = {}
    
    // SAF folder picker
    private val selectFolder = registry.register(
        "select_folder",
        lifecycleOwner,
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            // Persist the folder access permission
            persistFolderAccess(uri)
            val images = extractImagesFromFolderUri(uri)
            onImagesFound(images)
        }
    }
    
    /**
     * Open folder selection dialog
     */
    fun pickFolder() {
        selectFolder.launch(null)
    }
    
    /**
     * Persist folder access permission so it remains valid across app sessions
     */
    private fun persistFolderAccess(folderUri: Uri) {
        try {
            // Take persistable URI permission for read and write access
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(folderUri, takeFlags)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    /**
     * Extract all image URIs from a folder using SAF
     */
    private fun extractImagesFromFolderUri(folderUri: Uri): List<Uri> {
        val images = mutableListOf<Uri>()
        
        return try {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                folderUri,
                DocumentsContract.getTreeDocumentId(folderUri)
            )
            
            val cursor = context.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE
                ),
                null,
                null,
                null
            )
            
            cursor?.use {
                while (it.moveToNext()) {
                    try {
                        val documentId = it.getString(
                            it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                        )
                        val mimeType = it.getString(
                            it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                        )
                        
                        // Check if it's an image file and not a directory
                        if (mimeType?.startsWith("image/") == true &&
                            !mimeType.contains("folder") &&
                            !mimeType.contains("directory")
                        ) {
                            val documentUri = DocumentsContract.buildDocumentUriUsingTree(
                                folderUri,
                                documentId
                            )
                            images.add(documentUri)
                        }
                    } catch (e: Exception) {
                        continue
                    }
                }
            }
            
            images
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    /**
     * Extract image URIs from a file system path
     */
    fun extractImagesFromPath(path: String): List<Uri> {
        val images = mutableListOf<Uri>()
        
        return try {
            val folder = File(path)
            if (!folder.exists() || !folder.isDirectory) {
                return emptyList()
            }
            
            // Get all image files from the folder (non-recursive)
            folder.listFiles()?.forEach { file ->
                if (file.isFile && isImageFile(file)) {
                    val uri = Uri.fromFile(file)
                    images.add(uri)
                }
            }
            
            images
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    /**
     * Check if a file is an image based on extension
     */
    private fun isImageFile(file: File): Boolean {
        val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "raw", "heic")
        val extension = file.extension.lowercase()
        return extension in imageExtensions
    }
    
    /**
     * Get display name for image URI
     */
    fun getImageName(uri: Uri): String {
        return try {
            if (uri.scheme == "file") {
                File(uri.path ?: return uri.lastPathSegment ?: "Unknown").name
            } else {
                val cursor = context.contentResolver.query(uri, null, null, null, null)
                cursor?.use {
                    if (it.moveToFirst()) {
                        val nameIndex = it.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                        if (nameIndex >= 0) {
                            return@use it.getString(nameIndex)
                        }
                    }
                    null
                } ?: (uri.lastPathSegment ?: "Unknown")
            }
        } catch (e: Exception) {
            uri.lastPathSegment ?: "Unknown"
        }
    }
    
    /**
     * Get file size for image URI
     */
    fun getImageSize(uri: Uri): Long {
        return try {
            if (uri.scheme == "file") {
                File(uri.path ?: return 0L).length()
            } else {
                val cursor = context.contentResolver.query(uri, null, null, null, null)
                cursor?.use {
                    if (it.moveToFirst()) {
                        val sizeIndex = it.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                        if (sizeIndex >= 0) {
                            return@use it.getLong(sizeIndex)
                        }
                    }
                    null
                } ?: 0L
            }
        } catch (e: Exception) {
            0L
        }
    }
}

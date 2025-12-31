package com.example.exifdateeditor

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * Manages folder selection and image extraction using Storage Access Framework (SAF)
 * with persistent URI permissions
 */
class FolderPickerManager(
    private val context: Context,
    registry: ActivityResultRegistry,
    lifecycleOwner: LifecycleOwner
) : DefaultLifecycleObserver {
    
    private companion object {
        private const val PREFS_NAME = "folder_picker_prefs"
        private const val PREF_FOLDER_URI = "last_folder_uri"
    }
    
    var onImagesFound: (List<Uri>) -> Unit = {}
    
    private val selectFolder = registry.register(
        "select_folder",
        lifecycleOwner,
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            // Persist the URI permission so it works next time
            persistFolderAccess(uri)
            val images = extractImagesFromFolder(uri)
            onImagesFound(images)
        }
    }
    
    /**
     * Open folder picker dialog
     */
    fun pickFolder() {
        selectFolder.launch(null)
    }
    
    /**
     * Extract all image URIs from first level of selected folder
     */
    private fun extractImagesFromFolder(folderUri: Uri): List<Uri> {
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
                        // Skip problematic files
                        continue
                    }
                }
            }
            
            images
        } catch (e: Exception) {
            // Return empty list if folder traversal fails
            emptyList()
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
                    val nameIndex = it.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
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
                    val sizeIndex = it.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
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
    
    /**
     * Persist folder access permission so it remains valid across app sessions
     */
    private fun persistFolderAccess(folderUri: Uri) {
        try {
            // Take persistable URI permission for read access
            val takeFlags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or 
                           android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(folderUri, takeFlags)
            
            // Save the URI to SharedPreferences for reference
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(PREF_FOLDER_URI, folderUri.toString()).apply()
        } catch (e: Exception) {
            // Log but don't fail if persistence doesn't work (e.g., on some older devices)
            e.printStackTrace()
        }
    }
    
    /**
     * Get the last used folder URI if permission is still valid
     */
    fun getLastFolderUri(): Uri? {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val uriString = prefs.getString(PREF_FOLDER_URI, null) ?: return null
            Uri.parse(uriString)
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Check if we have persistent access to a folder URI
     */
    fun hasPersistentFolderAccess(folderUri: Uri): Boolean {
        return try {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                folderUri,
                DocumentsContract.getTreeDocumentId(folderUri)
            )
            val cursor = context.contentResolver.query(
                childrenUri,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                null,
                null,
                null
            )
            cursor?.use { it.count >= 0 } ?: false
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Restore access to the last used folder
     */
    fun restoreLastFolderAccess() {
        val lastUri = getLastFolderUri()
        if (lastUri != null && hasPersistentFolderAccess(lastUri)) {
            val images = extractImagesFromFolder(lastUri)
            if (images.isNotEmpty()) {
                onImagesFound(images)
            }
        }
    }

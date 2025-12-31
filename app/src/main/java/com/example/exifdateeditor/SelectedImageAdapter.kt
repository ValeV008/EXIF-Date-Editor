package com.example.exifdateeditor

import android.content.Context
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * Adapter for displaying selected images with EXIF date taken information
 */
class SelectedImageAdapter(
    private val images: MutableList<Uri>,
    private val context: Context
) : RecyclerView.Adapter<SelectedImageAdapter.ImageViewHolder>() {
    
    private val imageNames = mutableMapOf<Uri, String>()
    private val imageSizes = mutableMapOf<Uri, Long>()
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_selected_image, parent, false)
        return ImageViewHolder(view, context)
    }
    
    override fun onBindViewHolder(holder: ImageViewHolder, position: Int) {
        val uri = images[position]
        holder.bind(uri) {
            images.removeAt(position)
            imageNames.remove(uri)
            imageSizes.remove(uri)
            notifyItemRemoved(position)
        }
    }
    
    override fun getItemCount() = images.size
    
    fun setImageInfo(uri: Uri, name: String, size: Long) {
        imageNames[uri] = name
        imageSizes[uri] = size
    }
    
    class ImageViewHolder(itemView: View, private val context: Context) : RecyclerView.ViewHolder(itemView) {
        private val tvImageName: TextView = itemView.findViewById(R.id.tv_image_name)
        private val tvImageSize: TextView = itemView.findViewById(R.id.tv_image_size)
        private val tvDateTaken: TextView = itemView.findViewById(R.id.tv_date_taken)
        private val btnRemove: ImageButton = itemView.findViewById(R.id.btn_remove_image)
        
        fun bind(uri: Uri, onRemove: () -> Unit) {
            // Get filename from URI
            val fileName = try {
                val cursor = context.contentResolver.query(uri, null, null, null, null)
                cursor?.use {
                    if (it.moveToFirst()) {
                        val nameIndex = it.getColumnIndex(android.provider.MediaStore.MediaColumns.DISPLAY_NAME)
                        if (nameIndex >= 0) it.getString(nameIndex) else null
                    } else null
                }
            } catch (e: Exception) {
                null
            } ?: uri.lastPathSegment ?: "Unknown"
            
            tvImageName.text = fileName
            
            val sizeBytes = try {
                context.contentResolver.query(uri, null, null, null, null)?.use {
                    if (it.moveToFirst()) {
                        val sizeIndex = it.getColumnIndex(android.provider.MediaStore.MediaColumns.SIZE)
                        if (sizeIndex >= 0) it.getLong(sizeIndex) else 0L
                    } else 0L
                } ?: 0L
            } catch (e: Exception) {
                0L
            }
            
            tvImageSize.text = formatFileSize(sizeBytes)
            
            // Get and display EXIF date taken
            val dateTakenFormatted = ExifManager.getDateTakenFormatted(context, uri)
            tvDateTaken.text = "Date taken: $dateTakenFormatted"
            
            btnRemove.setOnClickListener {
                onRemove()
            }
        }
        
        private fun formatFileSize(bytes: Long): String {
            return when {
                bytes <= 0 -> "0 B"
                bytes < 1024 -> "$bytes B"
                bytes < 1024 * 1024 -> String.format("%.2f KB", bytes / 1024.0)
                else -> String.format("%.2f MB", bytes / (1024.0 * 1024))
            }
        }
    }
}

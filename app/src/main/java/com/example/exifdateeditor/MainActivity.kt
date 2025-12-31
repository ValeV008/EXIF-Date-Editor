package com.example.exifdateeditor

import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.*

class MainActivity : AppCompatActivity(), DatePickerDialogFragment.OnDateTimeSelectedListener {
    
    private lateinit var imagePickerManager: ImagePickerManager
    private lateinit var folderPickerManager: FolderPickerManager
    
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            Toast.makeText(this, "Permissions granted", Toast.LENGTH_SHORT).show()
            onPermissionsGranted()
        } else {
            val deniedPermissions = permissions.filter { !it.value }.keys
            Toast.makeText(
                this,
                "Permissions denied: ${deniedPermissions.joinToString(", ")}",
                Toast.LENGTH_LONG
            ).show()
        }
    }
    
    private val selectedImages = mutableListOf<Uri>()
    private lateinit var imageAdapter: SelectedImageAdapter
    
    private lateinit var tvSelectedCount: TextView
    private lateinit var rvSelectedImages: RecyclerView
    private lateinit var btnSelectImages: Button
    private lateinit var btnSelectFolder: Button
    private lateinit var btnClearSelection: Button
    private lateinit var btnSetExifDate: Button
    
    private var progressDialog: AlertDialog? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        initializeViews()
        imagePickerManager = ImagePickerManager(this, activityResultRegistry, this)
        imagePickerManager.onImagesSelected = ::onImagesSelected
        
        folderPickerManager = FolderPickerManager(this, activityResultRegistry, this)
        folderPickerManager.onImagesFound = ::onImagesSelected
        
        // Request permissions on app start
        requestStoragePermissions()
        
        // Restore persistent folder access if available
        folderPickerManager.restoreLastFolderAccess()
    }
    
    private fun initializeViews() {
        tvSelectedCount = findViewById(R.id.tv_selected_count)
        rvSelectedImages = findViewById(R.id.rv_selected_images)
        btnSelectImages = findViewById(R.id.btn_select_images)
        btnSelectFolder = findViewById(R.id.btn_select_folder)
        btnClearSelection = findViewById(R.id.btn_clear_selection)
        btnSetExifDate = findViewById(R.id.btn_set_exif_date)
        
        imageAdapter = SelectedImageAdapter(selectedImages, this)
        rvSelectedImages.layoutManager = LinearLayoutManager(this)
        rvSelectedImages.adapter = imageAdapter
        
        btnSelectImages.setOnClickListener {
            imagePickerManager.pickMultipleImages()
        }
        
        btnSelectFolder.setOnClickListener {
            folderPickerManager.pickFolder()
        }
        
        btnClearSelection.setOnClickListener {
            selectedImages.clear()
            imageAdapter.notifyDataSetChanged()
            updateSelectionCount()
        }
        
        btnSetExifDate.setOnClickListener {
            showDatePickerDialog()
        }
    }
    
    private fun requestStoragePermissions() {
        val missingPermissions = PermissionManager.getMissingPermissions(this)
        if (missingPermissions.isNotEmpty()) {
            requestPermissionLauncher.launch(missingPermissions.toTypedArray())
        } else {
            onPermissionsGranted()
        }
    }
    
    private fun onPermissionsGranted() {
        // UI is ready
    }
    
    private fun onImagesSelected(uris: List<Uri>) {
        if (uris.isEmpty()) return
        
        selectedImages.addAll(uris)
        imageAdapter.notifyItemRangeInserted(selectedImages.size - uris.size, uris.size)
        updateSelectionCount()
    }
    
    private fun updateSelectionCount() {
        tvSelectedCount.text = "Selected images: ${selectedImages.size}"
        btnSetExifDate.isEnabled = selectedImages.isNotEmpty()
    }
    
    private fun showDatePickerDialog() {
        val dialog = DatePickerDialogFragment()
        dialog.setListener(this)
        dialog.show(supportFragmentManager, "date_picker")
    }
    
    override fun onDateTimeSelected(date: Date) {
        startBatchExifProcessing(date)
    }
    
    private fun startBatchExifProcessing(date: Date) {
        val processor = BatchExifProcessor(this, selectedImages.toList())
        
        // Show progress dialog
        val progressView = layoutInflater.inflate(R.layout.dialog_progress, null)
        val progressBar = progressView.findViewById<ProgressBar>(R.id.progress_bar)
        val tvProgressText = progressView.findViewById<TextView>(R.id.tv_progress_text)
        val tvCurrentFile = progressView.findViewById<TextView>(R.id.tv_current_file)
        
        progressBar.max = selectedImages.size
        
        progressDialog = AlertDialog.Builder(this)
            .setTitle("Setting EXIF Date...")
            .setView(progressView)
            .setCancelable(false)
            .show()
        
        // Set callbacks
        processor.onProgress = { current, total, fileName ->
            progressBar.progress = current
            tvProgressText.text = "$current / $total"
            tvCurrentFile.text = fileName
        }
        
        processor.onComplete = { result ->
            progressDialog?.dismiss()
            showResultDialog(result)
        }
        
        // Start processing
        processor.setDateTakenForAll(date)
    }
    
    private fun showResultDialog(result: BatchOperationResult) {
        val resultView = layoutInflater.inflate(R.layout.dialog_result, null)
        val tvResultSummary = resultView.findViewById<TextView>(R.id.tv_result_summary)
        val tvSuccessCount = resultView.findViewById<TextView>(R.id.tv_success_count)
        val tvFailureCount = resultView.findViewById<TextView>(R.id.tv_failure_count)
        val tvErrorDetails = resultView.findViewById<TextView>(R.id.tv_error_details)
        
        tvResultSummary.text = "EXIF Date Update Complete"
        tvSuccessCount.text = "${result.successCount}"
        tvFailureCount.text = "${result.failureCount}"
        
        val errorText = if (result.errorMessages.isNotEmpty()) {
            result.errorMessages.entries.joinToString("\n") { (file, error) ->
                "• $file: $error"
            }
        } else {
            ""
        }
        tvErrorDetails.text = errorText
        
        AlertDialog.Builder(this)
            .setTitle("Result")
            .setView(resultView)
            .setPositiveButton("OK") { _, _ ->
                // Refresh display to show updated dates
                imageAdapter.notifyDataSetChanged()
            }
            .show()
    }
}



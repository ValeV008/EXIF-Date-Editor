package com.example.exifdateeditor

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import java.util.*

/**
 * Dialog for selecting date and time for EXIF data
 */
class DatePickerDialogFragment : DialogFragment() {
    
    interface OnDateTimeSelectedListener {
        fun onDateTimeSelected(date: Date)
    }
    
    private var listener: OnDateTimeSelectedListener? = null
    private var selectedDate: Calendar = Calendar.getInstance()
    private var selectedTime: Calendar = Calendar.getInstance()
    
    fun setListener(listener: OnDateTimeSelectedListener) {
        this.listener = listener
    }
    
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        selectedDate = Calendar.getInstance()
        selectedTime = Calendar.getInstance()
        
        showDateAndTimePickers()
        
        return AlertDialog.Builder(requireContext())
            .setTitle("Set EXIF Date & Time")
            .setMessage("Select date and time using the pickers")
            .setPositiveButton("OK") { _, _ ->
                val resultDate = Calendar.getInstance().apply {
                    set(Calendar.YEAR, selectedDate.get(Calendar.YEAR))
                    set(Calendar.MONTH, selectedDate.get(Calendar.MONTH))
                    set(Calendar.DAY_OF_MONTH, selectedDate.get(Calendar.DAY_OF_MONTH))
                    set(Calendar.HOUR_OF_DAY, selectedTime.get(Calendar.HOUR_OF_DAY))
                    set(Calendar.MINUTE, selectedTime.get(Calendar.MINUTE))
                    set(Calendar.SECOND, 0)
                }
                listener?.onDateTimeSelected(resultDate.time)
            }
            .setNegativeButton("Cancel", null)
            .create()
    }
    
    private fun showDateAndTimePickers() {
        // Show date picker first
        val datePicker = MaterialDatePicker.Builder.datePicker()
            .setTitleText("Select Date")
            .setSelection(MaterialDatePicker.todayInUtcMilliseconds())
            .build()
        
        datePicker.addOnPositiveButtonClickListener { selection ->
            selectedDate.timeInMillis = selection
            
            // Then show time picker
            val timePicker = MaterialTimePicker.Builder()
                .setTitleText("Select Time")
                .setTimeFormat(TimeFormat.CLOCK_24H)
                .setHour(selectedTime.get(Calendar.HOUR_OF_DAY))
                .setMinute(selectedTime.get(Calendar.MINUTE))
                .build()
            
            timePicker.addOnPositiveButtonClickListener {
                selectedTime.set(Calendar.HOUR_OF_DAY, timePicker.hour)
                selectedTime.set(Calendar.MINUTE, timePicker.minute)
            }
            
            timePicker.show(parentFragmentManager, "time_picker")
        }
        
        datePicker.show(parentFragmentManager, "date_picker")
    }
}

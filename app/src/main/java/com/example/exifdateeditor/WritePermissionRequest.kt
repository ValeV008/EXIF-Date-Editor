package com.example.exifdateeditor

import android.content.IntentSender
import android.net.Uri

data class WritePermissionRequest(
    val intentSender: IntentSender,
    val uri: Uri
)


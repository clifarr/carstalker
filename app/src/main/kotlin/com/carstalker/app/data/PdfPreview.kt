package com.carstalker.app.data

import android.net.Uri
import java.io.File

/**
 * A generated PDF held in app-private storage while the user previews it. Sharing and printing
 * both reuse [uri]/[file], so the document is written exactly once per export.
 */
data class PdfPreview(
    val file: File,
    val uri: Uri,
    val title: String,
    val shareSubject: String,
    val printJobName: String,
)

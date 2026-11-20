package org.example.project.ui.conversations

import androidx.compose.ui.draganddrop.DragAndDropEvent
import org.example.project.data.AttachmentFileData

expect fun DragAndDropEvent.readAttachmentFiles(
    onPicked: (AttachmentFileData) -> Unit,
    onError: (String) -> Unit,
    existingFileCount: Int,
    existingTotalBytes: Long
): Boolean

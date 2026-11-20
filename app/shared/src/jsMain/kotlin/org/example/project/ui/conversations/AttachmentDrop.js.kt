package org.example.project.ui.conversations

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.domDataTransferOrNull
import kotlin.js.ExperimentalWasmJsInterop
import org.example.project.data.AttachmentFileData
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Uint8Array
import org.w3c.dom.DataTransfer

@JsFun(
    "(dataTransfer, cb, onError, existingCount, existingTotal) => {" +
        " const files = Array.from(dataTransfer.files || []);" +
        " if (!files.length) return false;" +
        " if (existingCount + files.length > 5) { onError('Можно выбрать не более 5 файлов'); return true; }" +
        " if (files.some((f) => f.size > 25 * 1024 * 1024)) { onError('Файл превышает 25 МиБ'); return true; }" +
        " const total = files.reduce((sum, f) => sum + f.size, existingTotal);" +
        " if (total > 50 * 1024 * 1024) { onError('Общий размер файлов превышает 50 МиБ'); return true; }" +
        " const readNext = (index) => {" +
        "  if (index >= files.length) return;" +
        "  const f = files[index];" +
        "  const reader = new FileReader();" +
        "  reader.onload = () => { cb(f.name, f.type || '', reader.result); readNext(index + 1); };" +
        "  reader.onerror = () => onError('Не удалось прочитать файл: ' + f.name);" +
        "  reader.readAsArrayBuffer(f);" +
        " };" +
        " readNext(0);" +
        " return true;" +
        "}"
)
private external fun readDroppedFilesJs(
    dataTransfer: DataTransfer,
    cb: (String, String, ArrayBuffer) -> Unit,
    onError: (String) -> Unit,
    existingCount: Int,
    existingTotal: Double
): Boolean

@JsFun("(buffer, index) => new Uint8Array(buffer)[index]")
private external fun byteAt(buffer: ArrayBuffer, index: Int): Int

@OptIn(ExperimentalComposeUiApi::class, ExperimentalWasmJsInterop::class)
actual fun DragAndDropEvent.readAttachmentFiles(
    onPicked: (AttachmentFileData) -> Unit,
    onError: (String) -> Unit,
    existingFileCount: Int,
    existingTotalBytes: Long
): Boolean {
    val dataTransfer = transferData?.domDataTransferOrNull ?: return false
    return readDroppedFilesJs(dataTransfer, { name, mime, buffer ->
        val view = Uint8Array(buffer)
        val bytes = ByteArray(view.length) { index -> byteAt(buffer, index).toByte() }
        if (name.isNotBlank() && bytes.isNotEmpty()) {
            onPicked(AttachmentFileData(name, mime.ifBlank { null }, bytes))
        }
    }, onError, existingFileCount, existingTotalBytes.toDouble())
}

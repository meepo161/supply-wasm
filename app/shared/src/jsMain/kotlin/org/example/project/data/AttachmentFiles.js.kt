package org.example.project.data

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Uint8Array

@JsFun(
    "(cb, onError, existingCount, existingTotal) => {" +
        " const input = document.createElement('input');" +
        " input.type = 'file';" +
        " input.style.display = 'none';" +
        " input.multiple = true;" +
        " input.onchange = () => {" +
        "  const files = Array.from(input.files || []);" +
        "  if (!files.length) { input.remove(); return; }" +
        "  if (existingCount + files.length > 5) { onError('Можно выбрать не более 5 файлов'); input.remove(); return; }" +
        "  if (files.some((f) => f.size > 25 * 1024 * 1024)) { onError('Файл превышает 25 МиБ'); input.remove(); return; }" +
        "  const total = files.reduce((sum, f) => sum + f.size, existingTotal);" +
        "  if (total > 50 * 1024 * 1024) { onError('Общий размер файлов превышает 50 МиБ'); input.remove(); return; }" +
        "  const readNext = (index) => {" +
        "   if (index >= files.length) { input.remove(); return; }" +
        "   const f = files[index];" +
        "   const reader = new FileReader();" +
        "   reader.onload = () => { cb(f.name, f.type || '', reader.result); readNext(index + 1); };" +
        "   reader.onerror = () => { onError('Не удалось прочитать файл: ' + f.name); input.remove(); };" +
        "   reader.readAsArrayBuffer(f);" +
        "  };" +
        "  readNext(0);" +
        " };" +
        " document.body.appendChild(input);" +
        " input.click();" +
        "}"
)
private external fun pickFileJs(
    cb: (String, String, ArrayBuffer) -> Unit,
    onError: (String) -> Unit,
    existingCount: Int,
    existingTotal: Double
)

@JsFun("(buffer, index) => new Uint8Array(buffer)[index]")
private external fun byteAt(buffer: ArrayBuffer, index: Int): Int

@JsFun(
    "(name, mime, base64) => {" +
        " const bin = atob(base64);" +
        " const bytes = new Uint8Array(bin.length);" +
        " for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);" +
        " const blob = new Blob([bytes], { type: mime || 'application/octet-stream' });" +
        " const url = URL.createObjectURL(blob);" +
        " const a = document.createElement('a');" +
        " a.href = url;" +
        " a.download = name;" +
        " document.body.appendChild(a);" +
        " a.click();" +
        " a.remove();" +
        " setTimeout(() => URL.revokeObjectURL(url), 1000);" +
        "}"
)
private external fun saveFileJs(name: String, mime: String, base64: String)

actual fun pickAttachmentFile(
    onPicked: (AttachmentFileData) -> Unit,
    onError: (String) -> Unit,
    existingFileCount: Int,
    existingTotalBytes: Long
) {
    pickFileJs({ name, mime, buffer ->
        val view = Uint8Array(buffer)
        val bytes = ByteArray(view.length) { index -> byteAt(buffer, index).toByte() }
        if (name.isNotBlank() && bytes.isNotEmpty()) {
            onPicked(AttachmentFileData(name, mime.ifBlank { null }, bytes))
        }
    }, onError, existingFileCount, existingTotalBytes.toDouble())
}

@OptIn(ExperimentalEncodingApi::class)
actual fun saveAttachmentFile(fileName: String, mimeType: String?, bytes: ByteArray) {
    saveFileJs(fileName, mimeType ?: "application/octet-stream", Base64.encode(bytes))
}

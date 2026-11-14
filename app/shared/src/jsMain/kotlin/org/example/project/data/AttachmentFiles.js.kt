package org.example.project.data

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@JsFun(
    "(cb) => {" +
        " const input = document.createElement('input');" +
        " input.type = 'file';" +
        " input.style.display = 'none';" +
        " input.onchange = () => {" +
        "  const f = input.files && input.files[0];" +
        "  if (!f) { input.remove(); return; }" +
        "  const reader = new FileReader();" +
        "  reader.onload = () => { cb(f.name, f.type || '', String(reader.result)); input.remove(); };" +
        "  reader.onerror = () => { input.remove(); };" +
        "  reader.readAsDataURL(f);" +
        " };" +
        " document.body.appendChild(input);" +
        " input.click();" +
        "}"
)
private external fun pickFileJs(cb: (String, String, String) -> Unit)

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

@OptIn(ExperimentalEncodingApi::class)
actual fun pickAttachmentFile(onPicked: (AttachmentFileData) -> Unit) {
    pickFileJs { name, mime, dataUrl ->
        val base64 = dataUrl.substringAfter(',', "")
        val bytes = if (base64.isEmpty()) null else runCatching { Base64.decode(base64) }.getOrNull()
        if (name.isNotBlank() && bytes != null && bytes.isNotEmpty()) {
            onPicked(AttachmentFileData(name, mime.ifBlank { null }, bytes))
        }
    }
}

@OptIn(ExperimentalEncodingApi::class)
actual fun saveAttachmentFile(fileName: String, mimeType: String?, bytes: ByteArray) {
    saveFileJs(fileName, mimeType ?: "application/octet-stream", Base64.encode(bytes))
}

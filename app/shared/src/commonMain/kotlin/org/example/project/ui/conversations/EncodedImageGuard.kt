package org.example.project.ui.conversations

private const val MAX_IMAGE_PIXELS = 25_000_000L
private const val MAX_IMAGE_SIDE = 16_384

fun areImageDimensionsSafe(width: Int, height: Int): Boolean =
    width > 0 && height > 0 && width <= MAX_IMAGE_SIDE && height <= MAX_IMAGE_SIDE &&
        width.toLong() * height.toLong() <= MAX_IMAGE_PIXELS

fun <T> withGuardedEncodedImage(bytes: ByteArray, decoder: () -> T): T? {
    val dimensions = encodedImageDimensions(bytes) ?: return null
    if (!areImageDimensionsSafe(dimensions.width, dimensions.height)) return null
    return decoder()
}

private data class EncodedDimensions(val width: Int, val height: Int)

private fun encodedImageDimensions(bytes: ByteArray): EncodedDimensions? = when {
    bytes.startsWith(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) -> pngDimensions(bytes)
    bytes.startsWith(0xff, 0xd8) -> jpegDimensions(bytes)
    bytes.startsWith(0x47, 0x49, 0x46, 0x38) -> gifDimensions(bytes)
    bytes.startsWith(0x52, 0x49, 0x46, 0x46) && bytes.matchesAt(8, 0x57, 0x45, 0x42, 0x50) -> webpDimensions(bytes)
    else -> null
}

private fun pngDimensions(bytes: ByteArray): EncodedDimensions? {
    if (bytes.size < 24 || !bytes.matchesAt(12, 0x49, 0x48, 0x44, 0x52) || bytes.be32(8) != 13) return null
    return EncodedDimensions(bytes.be32(16), bytes.be32(20))
}

private fun gifDimensions(bytes: ByteArray): EncodedDimensions? {
    if (bytes.size < 10 || !bytes.matchesAt(4, 0x37, 0x61) && !bytes.matchesAt(4, 0x39, 0x61)) return null
    return EncodedDimensions(bytes.le16(6), bytes.le16(8))
}

private fun jpegDimensions(bytes: ByteArray): EncodedDimensions? {
    var offset = 2
    while (offset + 4 <= bytes.size) {
        if (bytes.u8(offset) != 0xff) {
            offset++
            continue
        }
        while (offset < bytes.size && bytes.u8(offset) == 0xff) offset++
        if (offset >= bytes.size) return null
        val marker = bytes.u8(offset++)
        if (marker == 0xd9 || marker == 0xda) return null
        if (marker == 0x01 || marker in 0xd0..0xd7) continue
        if (offset + 2 > bytes.size) return null
        val length = bytes.be16(offset)
        if (length < 2 || offset + length > bytes.size) return null
        if (marker in JPEG_SOF_MARKERS) {
            if (length < 7) return null
            return EncodedDimensions(bytes.be16(offset + 5), bytes.be16(offset + 3))
        }
        offset += length
    }
    return null
}

private val JPEG_SOF_MARKERS = setOf(
    0xc0, 0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7, 0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf
)

private fun webpDimensions(bytes: ByteArray): EncodedDimensions? {
    if (bytes.size < 30) return null
    val chunkSize = bytes.le32(16)
    return when {
        bytes.matchesAt(12, 0x56, 0x50, 0x38, 0x58) && chunkSize >= 10 -> EncodedDimensions(
            width = 1 + bytes.le24(24),
            height = 1 + bytes.le24(27)
        )
        bytes.matchesAt(12, 0x56, 0x50, 0x38, 0x20) && chunkSize >= 10 -> {
            if (!bytes.matchesAt(23, 0x9d, 0x01, 0x2a)) return null
            EncodedDimensions(bytes.le16(26) and 0x3fff, bytes.le16(28) and 0x3fff)
        }
        bytes.matchesAt(12, 0x56, 0x50, 0x38, 0x4c) && chunkSize >= 5 && bytes.u8(20) == 0x2f -> {
            val b1 = bytes.u8(21)
            val b2 = bytes.u8(22)
            val b3 = bytes.u8(23)
            val b4 = bytes.u8(24)
            EncodedDimensions(
                width = 1 + b1 + ((b2 and 0x3f) shl 8),
                height = 1 + (b2 ushr 6) + (b3 shl 2) + ((b4 and 0x0f) shl 10)
            )
        }
        else -> null
    }
}

private fun ByteArray.startsWith(vararg prefix: Int): Boolean =
    size >= prefix.size && prefix.indices.all { this[it].toInt() and 0xff == prefix[it] }

private fun ByteArray.matchesAt(offset: Int, vararg expected: Int): Boolean =
    offset >= 0 && offset + expected.size <= size && expected.indices.all {
        this[offset + it].toInt() and 0xff == expected[it]
    }

private fun ByteArray.u8(offset: Int): Int = this[offset].toInt() and 0xff
private fun ByteArray.be16(offset: Int): Int = (u8(offset) shl 8) or u8(offset + 1)
private fun ByteArray.le16(offset: Int): Int = u8(offset) or (u8(offset + 1) shl 8)
private fun ByteArray.le24(offset: Int): Int = u8(offset) or (u8(offset + 1) shl 8) or (u8(offset + 2) shl 16)
private fun ByteArray.le32(offset: Int): Long =
    le16(offset).toLong() or (le16(offset + 2).toLong() shl 16)
private fun ByteArray.be32(offset: Int): Int =
    (u8(offset) shl 24) or (u8(offset + 1) shl 16) or (u8(offset + 2) shl 8) or u8(offset + 3)

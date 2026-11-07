package com.example.storage

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Inflater

private val pngSignature = byteArrayOf(0x89.toByte(), 80, 78, 71, 13, 10, 26, 10)
private const val IHDR = 0x49484452
private const val PLTE = 0x504c5445
private const val TRNS = 0x74524e53
private const val IDAT = 0x49444154
private const val IEND = 0x49454e44
private const val MAX_PNG_CHUNKS = 4096

/** Verify framing and checksums, and pass only pixel-related chunks to ImageIO. */
internal fun sanitizeAvatarPng(bytes: ByteArray): ByteArray {
    if (bytes.size < 33 || !pngSignature.indices.all { pngSignature[it] == bytes[it] }) invalidImage()
    val data = ByteBuffer.wrap(bytes)
    if (data.getInt(8) != 13 || data.getInt(12) != IHDR) invalidImage()
    val depth = data.get(24).toInt()
    val colorType = data.get(25).toInt()
    val expectedImageBytes = pngRasterBytes(data, depth, colorType)
    val clean = ByteArrayOutputStream().apply { write(pngSignature) }
    val crc = CRC32()
    val inflater = Inflater()
    val buffer = ByteArray(8 * 1024)
    var offset = pngSignature.size
    var chunks = 0
    var paletteSize = 0
    var seenTransparency = false
    var seenData = false
    var dataEnded = false
    var inflatedBytes = 0L
    try {
        while (offset <= bytes.size - 12) {
            if (++chunks > MAX_PNG_CHUNKS) invalidImage()
            val length = data.getInt(offset)
            if (length < 0 || length > bytes.size - offset - 12) invalidImage()
            val type = data.getInt(offset + 4)
            val payload = offset + 8
            val next = payload + length + 4
            crc.reset()
            crc.update(bytes, offset + 4, length + 4)
            if (crc.value.toInt() != data.getInt(payload + length)) invalidImage()
            when (type) {
                IHDR -> if (offset != pngSignature.size) invalidImage()
                PLTE -> {
                    if (paletteSize > 0 || seenTransparency || seenData || colorType == 0 || colorType == 4 ||
                        length !in 3..768 || length % 3 != 0
                    ) {
                        invalidImage()
                    }
                    paletteSize = length / 3
                    if (colorType == 3 && paletteSize > (1 shl depth)) invalidImage()
                }
                TRNS -> {
                    if (seenTransparency || seenData) invalidImage()
                    val validLength = when (colorType) {
                        0 -> length == 2
                        2 -> length == 6
                        3 -> length in 1..paletteSize
                        else -> false
                    }
                    if (!validLength) invalidImage()
                    seenTransparency = true
                }
                IDAT -> {
                    if (dataEnded || colorType == 3 && paletteSize == 0) invalidImage()
                    seenData = true
                    inflater.setInput(bytes, payload, length)
                    while (!inflater.needsInput() && !inflater.finished()) {
                        val count = inflater.inflate(buffer)
                        inflatedBytes += count
                        if (inflatedBytes > expectedImageBytes ||
                            count == 0 && !inflater.needsInput() && !inflater.finished()
                        ) invalidImage()
                    }
                    if (inflater.remaining != 0) invalidImage()
                }
                IEND -> {
                    if (length != 0 || next != bytes.size || !seenData || !inflater.finished() ||
                        inflatedBytes != expectedImageBytes
                    ) invalidImage()
                    clean.write(bytes, offset, next - offset)
                    return clean.toByteArray()
                }
                else -> if (type and 0x20000000 == 0) invalidImage() // Unknown critical chunk.
            }
            if (type == IHDR || type == PLTE || type == TRNS || type == IDAT) {
                clean.write(bytes, offset, next - offset)
            }
            if (seenData && type != IDAT) dataEnded = true
            offset = next
        }
    } catch (_: DataFormatException) {
        invalidImage()
    } finally {
        inflater.end()
    }
    invalidImage()
}

private fun pngRasterBytes(data: ByteBuffer, depth: Int, colorType: Int): Long {
    val width = data.getInt(16)
    val height = data.getInt(20)
    checkAvatarDimensions(width, height)
    if (data.get(26).toInt() != 0 || data.get(27).toInt() != 0) invalidImage()
    val channels = when (colorType) {
        0, 3 -> 1
        2 -> 3
        4 -> 2
        6 -> 4
        else -> invalidImage()
    }
    val validDepth = when (colorType) {
        0 -> depth in setOf(1, 2, 4, 8, 16)
        3 -> depth in setOf(1, 2, 4, 8)
        else -> depth == 8 || depth == 16
    }
    if (!validDepth) invalidImage()
    val bitsPerPixel = channels * depth
    fun passBytes(columns: Int, rows: Int): Long =
        if (columns == 0 || rows == 0) 0 else ((columns * bitsPerPixel + 7) / 8L + 1) * rows

    return when (data.get(28).toInt()) {
        0 -> passBytes(width, height)
        1 -> { // Adam7 pass geometry, including one filter byte for each nonempty scanline.
            val xStarts = intArrayOf(0, 4, 0, 2, 0, 1, 0)
            val yStarts = intArrayOf(0, 0, 4, 0, 2, 0, 1)
            val xSteps = intArrayOf(8, 8, 4, 4, 2, 2, 1)
            val ySteps = intArrayOf(8, 8, 8, 4, 4, 2, 2)
            xStarts.indices.sumOf { pass ->
                passBytes(
                    (width - xStarts[pass] + xSteps[pass] - 1) / xSteps[pass],
                    (height - yStarts[pass] + ySteps[pass] - 1) / ySteps[pass],
                )
            }
        }
        else -> invalidImage()
    }
}

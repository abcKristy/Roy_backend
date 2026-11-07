package com.example.storage

import java.awt.AlphaComposite
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream
import javax.imageio.stream.MemoryCacheImageOutputStream

private const val MAX_INPUT_EDGE = 8192
private const val MAX_INPUT_PIXELS = 16_000_000L
private const val MAX_OUTPUT_EDGE = 1024

internal class InvalidAvatarImageException : IOException()

internal object AvatarImageProcessor {
    fun process(bytes: ByteArray, type: AvatarContentType): AvatarImage {
        val raster = try {
            when (type) {
                AvatarContentType.PNG -> decode(sanitizeAvatarPng(bytes), type)
                AvatarContentType.JPEG -> {
                    // The JPEG decoder can otherwise recover some missing final markers.
                    if (bytes.size < 4 || bytes[bytes.lastIndex - 1] != 0xff.toByte() ||
                        bytes.last() != 0xd9.toByte()
                    ) invalidImage()
                    decode(bytes, type)
                }
            }
        } catch (_: IOException) {
            invalidImage()
        } catch (_: RuntimeException) {
            // Reader implementations can raise unchecked errors for invalid input fields.
            invalidImage()
        }

        val encoded = ByteArrayOutputStream()
        MemoryCacheImageOutputStream(encoded).use { output ->
            check(ImageIO.write(raster, type.name, output)) { "Avatar image writer unavailable" }
        }
        if (encoded.size() !in 1..MAX_STORED_AVATAR_BYTES) invalidImage()
        return AvatarImage(encoded.toByteArray(), type)
    }

    private fun decode(bytes: ByteArray, type: AvatarContentType): BufferedImage =
        MemoryCacheImageInputStream(bytes.inputStream()).use { input ->
            val readers = ImageIO.getImageReaders(input)
            if (!readers.hasNext()) invalidImage()
            val reader = readers.next()
            try {
                if (!reader.formatName.equals(type.name, ignoreCase = true)) invalidImage()
                var warned = false
                reader.addIIOReadWarningListener { _, _ -> warned = true }
                reader.setInput(input, true, true)
                val width = reader.getWidth(0)
                val height = reader.getHeight(0)
                checkAvatarDimensions(width, height)
                val sample = maxOf(1, maxOf(width, height) / MAX_OUTPUT_EDGE)
                val params = reader.defaultReadParam
                params.setSourceSubsampling(sample, sample, 0, 0)
                val source = reader.read(0, params) ?: invalidImage()
                if (warned) invalidImage()
                copyRaster(source, width, height, type)
            } finally {
                reader.dispose()
            }
        }

    private fun copyRaster(source: BufferedImage, width: Int, height: Int, type: AvatarContentType): BufferedImage {
        val inputEdge = maxOf(width, height)
        val outputEdge = minOf(inputEdge, MAX_OUTPUT_EDGE)
        val raster = BufferedImage(
            maxOf(1, width * outputEdge / inputEdge),
            maxOf(1, height * outputEdge / inputEdge),
            if (type == AvatarContentType.PNG && source.colorModel.hasAlpha()) BufferedImage.TYPE_INT_ARGB
            else BufferedImage.TYPE_INT_RGB,
        )
        val graphics = raster.createGraphics()
        try {
            graphics.composite = AlphaComposite.Src
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            graphics.drawImage(source, 0, 0, raster.width, raster.height, null)
        } finally {
            graphics.dispose()
        }
        return raster
    }
}

internal fun checkAvatarDimensions(width: Int, height: Int) {
    if (width !in 1..MAX_INPUT_EDGE || height !in 1..MAX_INPUT_EDGE || width.toLong() * height > MAX_INPUT_PIXELS) {
        invalidImage()
    }
}

internal fun invalidImage(): Nothing = throw InvalidAvatarImageException()

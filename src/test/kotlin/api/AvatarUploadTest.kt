package com.example.api

import com.example.auth.Argon2idPasswordHasher
import com.example.auth.PasswordHashingWork
import com.example.auth.ProfileService
import com.example.persistence.DatabaseRuntime
import com.example.storage.AvatarContentType
import com.example.storage.AvatarImage
import com.example.storage.LocalAvatarStore
import com.example.storage.restrictToOwner
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.response.respondText
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.CRC32
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AvatarUploadTest {
    @Test
    fun `stages fresh JPEG and PNG bytes without source metadata`() = testApplication {
        withStore { _, store ->
            val jpeg = image("jpeg")
            val exif = jpegExif()
            val withExif = jpeg.copyOfRange(0, 2) +
                byteArrayOf(0xff.toByte(), 0xe1.toByte(), 0, (exif.size + 2).toByte()) +
                exif + jpeg.copyOfRange(2, jpeg.size)
            val png = image("png")
            val withText = insertPngChunk(
                png, "tEXt", "Comment\u0000private-png-metadata\r\n--avatar-fixture-prefix".encodeToByteArray(),
            )
            val cases = listOf(AvatarContentType.JPEG to withExif, AvatarContentType.PNG to withText)
            for ((type, source) in cases) {
                assertTrue(source.toString(Charsets.ISO_8859_1).contains("private-"))
                val response = upload(multipart(part(source, type.mimeType)), quotedBoundary = true)
                assertEquals(HttpStatusCode.OK, response.status)
                val stored = assertNotNull(store.read(response.bodyAsText()))
                assertEquals(type, stored.contentType)
                assertFalse(stored.bytes.toString(Charsets.ISO_8859_1).contains("private-"))
                val raster = assertNotNull(ImageIO.read(stored.bytes.inputStream()))
                assertEquals(24, raster.width)
                assertEquals(16, raster.height)
                if (type == AvatarContentType.PNG) assertEquals(0x4060a0ff, raster.getRGB(10, 10))
            }
        }
    }

    @Test
    fun `MIME tagged image part can be stored without a filename`() = testApplication {
        withStore { _, store ->
            val png = image("png")
            val contentType = "image/png; note=\"a \\\"quote\\\"; and comma, inside\""
            val response = upload(multipart(part(png, contentType, disposition = "form-data; NAME=file")))
            assertEquals(HttpStatusCode.OK, response.status)
            val stored = assertNotNull(store.read(response.bodyAsText()))
            assertEquals(AvatarContentType.PNG, stored.contentType)
            assertEquals(0x4060a0ff, assertNotNull(ImageIO.read(stored.bytes.inputStream())).getRGB(10, 10))
        }
    }

    @Test
    fun `malformed part parameters cannot create or change an object`() = testApplication {
        withStore { root, store ->
            val png = image("png")
            val oldImage = AvatarImage(png, AvatarContentType.PNG)
            val oldKey = store.save(oldImage)
            val invalid = listOf(
                "unterminated MIME parameter" to part(png, "image/png; x=\"unterminated"),
                "unterminated filename" to part(png, "image/png", disposition = "form-data; name=file; filename=\"open"),
                "unquoted space" to part(png, "image/png; x=two words"),
                "missing equals" to part(png, "image/png; x"),
                "duplicate file parameter" to part(png, "image/png", disposition = "form-data; name=file; NAME=file"),
                "duplicate filename" to part(
                    png, "image/png", disposition = "form-data; name=file; filename=one; FILENAME=two",
                ),
            )
            for ((label, body) in invalid) {
                assertFailure(upload(multipart(body)), 422, label)
                assertPreserved(root, store, oldKey, oldImage)
            }
        }
    }

    @Test
    fun `streamed file at five MiB succeeds and one extra byte returns 413`() = testApplication {
        withStore { root, store ->
            val png = image("png")
            val maxFileBytes = 5_242_880
            fun padded(size: Int) = insertPngChunk(
                png, "tEXt", "Comment\u0000".encodeToByteArray() + ByteArray(size - png.size - 20) { 65 },
            )
            val exact = padded(maxFileBytes)
            assertEquals(maxFileBytes, exact.size)
            val accepted = upload(multipart(part(exact, "image/png")))
            assertEquals(HttpStatusCode.OK, accepted.status)
            val oldKey = accepted.bodyAsText()
            val oldImage = assertNotNull(store.read(oldKey))
            assertTrue(oldImage.bytes.size < 1_000)
            val tooBig = padded(maxFileBytes + 1)
            assertEquals(maxFileBytes + 1, tooBig.size)
            assertFailure(upload(multipart(part(tooBig, "image/png", extraHeader = "Content-Length: 1\r\n"))), 413)
            assertPreserved(root, store, oldKey, oldImage)
        }
    }

    @Test
    fun `invalid or incomplete requests leave the existing stored object intact`() = testApplication {
        withStore { root, store ->
            val png = image("png")
            val jpeg = image("jpeg")
            val oldImage = AvatarImage(png, AvatarContentType.PNG)
            val oldKey = store.save(oldImage)
            val pngCorrupt = png.clone().apply { this[45] = (this[45].toInt() xor 1).toByte() }
            val invalid = listOf(
                "spoofed JPEG" to multipart(part(png, "image/jpeg")),
                "unsupported MIME" to multipart(part(png, "image/gif")),
                "unsupported bytes" to multipart(part("GIF89a".encodeToByteArray(), "image/png")),
                "truncated PNG" to multipart(part(png.copyOf(png.size - 12), "image/png")),
                "corrupt PNG" to multipart(part(pngCorrupt, "image/png")),
                "truncated PNG zlib with valid chunk CRC" to multipart(part(truncatePngDeflate(png), "image/png")),
                "truncated JPEG" to multipart(part(jpeg.copyOf(jpeg.size - 2), "image/jpeg")),
                "oversized edge" to multipart(part(withPngDimensions(png, 9000, 16), "image/png")),
                "oversized pixel count" to multipart(part(withPngDimensions(png, 5000, 5000), "image/png")),
                "empty file" to multipart(part(byteArrayOf(), "image/png")),
                "missing file" to multipart(part(png, "image/png", name = "unrelated")),
                "duplicate file" to multipart(part(png, "image/png"), part(jpeg, "image/jpeg")),
                "missing closing delimiter" to ("--avatar-fixture\r\n".encodeToByteArray() + part(png, "image/png")),
                "trailing data" to (multipart(part(png, "image/png")) + "trailing".encodeToByteArray()),
                "large headers" to multipart(part(png, "image/png", extraHeader = "X-Large: ${"a".repeat(9_000)}\r\n")),
            )
            for ((label, body) in invalid) {
                assertFailure(upload(body), 422, label)
                assertPreserved(root, store, oldKey, oldImage)
            }
        }
    }

    @Test
    fun `large PNG is resized within the stored limit with transparency`() = testApplication {
        withStore { _, store ->
            val source = image("png", width = 1536, height = 768)
            val response = upload(multipart(part(source, "image/png")))
            assertEquals(HttpStatusCode.OK, response.status)
            val stored = assertNotNull(store.read(response.bodyAsText()))
            assertTrue(stored.bytes.size in 1..5_242_880)
            val raster = assertNotNull(ImageIO.read(stored.bytes.inputStream()))
            assertEquals(1024, raster.width)
            assertEquals(512, raster.height)
            assertEquals(0x40, raster.getRGB(500, 250) ushr 24)
        }
    }

    private suspend fun ApplicationTestBuilder.withStore(block: suspend (Path, LocalAvatarStore) -> Unit) {
        val root = Files.createTempDirectory("avatar-upload-test-").toRealPath()
        try {
            restrictToOwner(root)
            val store = LocalAvatarStore(root)
            val database = object : DatabaseRuntime {
                override suspend fun isReady(): Boolean = error("No database call expected")
                override fun close() = Unit
            }
            val profile = ProfileService(database, PasswordHashingWork(Argon2idPasswordHasher()), store)
            application {
                configureTransport()
                routing {
                    put("/avatar-component") {
                        assertEquals(null, call.request.headers[HttpHeaders.ContentLength])
                        call.respondText(profile.stageAvatar(call.receiveAvatarImage()))
                    }
                }
            }
            block(root, store)
        } finally {
            check(root.toFile().deleteRecursively())
        }
    }

    private suspend fun ApplicationTestBuilder.upload(bytes: ByteArray, quotedBoundary: Boolean = false) =
        client.put("/avatar-component") {
            setBody(object : OutgoingContent.WriteChannelContent() {
                override val contentType: ContentType = ContentType.parse(
                    if (quotedBoundary) "Multipart/Form-Data; Boundary=\"avatar-fixture\""
                    else "multipart/form-data; boundary=avatar-fixture",
                )

                // Unknown contentLength. Split headers and file across the same streamed request.
                override suspend fun writeTo(channel: ByteWriteChannel) {
                    for (offset in bytes.indices step 4093) {
                        channel.writeFully(bytes, offset, minOf(offset + 4093, bytes.size))
                        channel.flush()
                    }
                }
            })
        }

    private suspend fun assertFailure(response: io.ktor.client.statement.HttpResponse, status: Int, label: String = "") {
        assertEquals(status, response.status.value, label)
        val error = Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("error").jsonObject
        assertEquals("INVALID_FIELD", error.getValue("code").jsonPrimitive.content, label)
    }

    private suspend fun assertPreserved(root: Path, store: LocalAvatarStore, oldKey: String, oldImage: AvatarImage) {
        val stored = assertNotNull(store.read(oldKey))
        assertContentEquals(oldImage.bytes, stored.bytes)
        assertEquals(oldImage.contentType, stored.contentType)
        Files.list(root).use { entries -> assertEquals(listOf(oldKey), entries.map { it.fileName.toString() }.toList()) }
    }

    private fun multipart(vararg parts: ByteArray) = ByteArrayOutputStream().apply {
        for (part in parts) {
            write("--avatar-fixture\r\n".encodeToByteArray())
            write(part)
            write("\r\n".encodeToByteArray())
        }
        write("--avatar-fixture--\r\n".encodeToByteArray())
    }.toByteArray()

    private fun part(
        bytes: ByteArray,
        mimeType: String,
        name: String = "file",
        extraHeader: String = "",
        disposition: String = "form-data; name=\"$name\"; filename=\"photo.bin\"",
    ) =
        ("Content-Disposition: $disposition\r\n" +
            "Content-Type: $mimeType\r\n$extraHeader\r\n").encodeToByteArray() + bytes

    private fun image(format: String, width: Int = 24, height: Int = 16): ByteArray {
        val raster = BufferedImage(
            width, height, if (format == "png") BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB,
        )
        val row = IntArray(width) { 0x4060a0ff }
        for (y in 0 until height) raster.setRGB(0, y, width, 1, row, 0, width)
        return ByteArrayOutputStream().apply { check(ImageIO.write(raster, format, this)) }.toByteArray()
    }

    private fun jpegExif(): ByteArray {
        val description = "private-jpeg-metadata\u0000".encodeToByteArray()
        // TIFF big endian, one ImageDescription entry, no additional IFD.
        val tiff = ByteBuffer.allocate(26 + description.size)
            .putShort(0x4d4d).putShort(42).putInt(8).putShort(1)
            .putShort(0x010e).putShort(2).putInt(description.size).putInt(26).putInt(0).put(description)
        return "Exif\u0000\u0000".encodeToByteArray() + tiff.array()
    }

    private fun withPngDimensions(png: ByteArray, width: Int, height: Int): ByteArray = png.clone().apply pngBytes@ {
        ByteBuffer.wrap(this).putInt(16, width).putInt(20, height)
        ByteBuffer.wrap(this).putInt(29, CRC32().apply { update(this@pngBytes, 12, 17) }.value.toInt())
    }

    private fun truncatePngDeflate(png: ByteArray): ByteArray {
        val offset = 33
        val length = ByteBuffer.wrap(png).getInt(offset)
        check(png.copyOfRange(offset + 4, offset + 8).decodeToString() == "IDAT")
        val data = png.copyOfRange(offset + 8, offset + 8 + length - 4)
        return png.copyOfRange(0, offset) + pngChunk("IDAT", data) + png.copyOfRange(offset + length + 12, png.size)
    }

    private fun insertPngChunk(png: ByteArray, name: String, data: ByteArray): ByteArray =
        // Immediately after the fixed PNG signature and IHDR chunk.
        png.copyOfRange(0, 33) + pngChunk(name, data) + png.copyOfRange(33, png.size)

    private fun pngChunk(name: String, data: ByteArray): ByteArray {
        val type = name.encodeToByteArray()
        val crc = CRC32().apply { update(type); update(data) }
        return ByteBuffer.allocate(12 + data.size)
            .putInt(data.size).put(type).put(data).putInt(crc.value.toInt()).array()
    }
}

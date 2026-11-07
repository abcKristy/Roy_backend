package com.example.api

import com.example.storage.AvatarContentType
import com.example.storage.AvatarImage
import com.example.storage.AvatarImageProcessor
import com.example.storage.InvalidAvatarImageException
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.utils.io.readTo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import java.io.IOException

private const val MAX_UPLOAD_BYTES = 5 * 1024 * 1024
private const val MAX_PART_HEADERS_BYTES = 8 * 1024
private const val MAX_MULTIPART_BYTES = MAX_UPLOAD_BYTES + MAX_PART_HEADERS_BYTES + 160
private const val HEADER_TOKEN = """[a-zA-Z0-9!#\x24%&'*+.^_\x60|~-]++"""
private const val QUOTED_TEXT_CHAR = """[\t\x20-\x21\x23-\x5b\x5d-\x7e\x80-\xff]"""
private const val QUOTED_PAIR_CHAR = """[\t\x20-\x7e\x80-\xff]"""
private val boundaryCharacters = Regex("[a-zA-Z0-9'()+_,./:=? -]{1,70}")
private val headerName = Regex(HEADER_TOKEN)
private val headerParameter = Regex(
    """;[ \t]*($HEADER_TOKEN)[ \t]*=[ \t]*(?:($HEADER_TOKEN)|"((?:$QUOTED_TEXT_CHAR|\\$QUOTED_PAIR_CHAR)*+)")[ \t]*""",
)
private val quotedPair = Regex("""\\($QUOTED_PAIR_CHAR)""")
private val avatarDecoding = Dispatchers.Default.limitedParallelism(2)

/** The profile route calls this before handing an image to the storage/DB swap. */
internal suspend fun ApplicationCall.receiveAvatarImage(): AvatarImage {
    val boundary = avatarBoundary()
    val (bytes, type) = readAvatarPart(boundary)
    return try {
        withContext(avatarDecoding) { AvatarImageProcessor.process(bytes, type) }
    } catch (_: InvalidAvatarImageException) {
        invalidAvatar()
    }
}

private fun ApplicationCall.avatarBoundary(): String {
    val rawType = request.headers.getAll(HttpHeaders.ContentType)?.singleOrNull() ?: invalidAvatar()
    val (type, parameters) = parseAvatarHeader(rawType)
    if (!type.equals("multipart/form-data", ignoreCase = true)) invalidAvatar()
    val boundary = parameters["boundary"] ?: invalidAvatar()
    if (!boundaryCharacters.matches(boundary) || boundary.endsWith(' ')) invalidAvatar()
    return boundary
}

private suspend fun ApplicationCall.readAvatarPart(boundary: String): Pair<ByteArray, AvatarContentType> {
    // Read a bounded envelope, with one extra byte to detect overflow. Neither a top-level
    // nor a part Content-Length establishes the actual size of the file.
    val body = try {
        Buffer().use { buffer ->
            val count = receiveChannel().readTo(buffer, MAX_MULTIPART_BYTES + 1L)
            if (count > MAX_MULTIPART_BYTES) avatarTooLarge()
            buffer.readByteArray()
        }
    } catch (_: IOException) {
        invalidAvatar()
    }
    // ISO-8859-1 keeps every string offset equal to its byte offset for delimiter matching.
    val wire = body.toString(Charsets.ISO_8859_1)
    val opening = "--$boundary\r\n"
    if (!wire.startsWith(opening)) invalidAvatar()
    val headerEnd = wire.indexOf("\r\n\r\n", opening.length)
    if (headerEnd < 0 || headerEnd - opening.length > MAX_PART_HEADERS_BYTES) invalidAvatar()
    val payloadStart = headerEnd + 4
    val payloadEnd = findAvatarEnd(wire, boundary, payloadStart)
    val size = payloadEnd - payloadStart
    if (size > MAX_UPLOAD_BYTES) avatarTooLarge()
    if (size == 0) invalidAvatar()
    val type = avatarPartType(wire.substring(opening.length, headerEnd))
    return body.copyOfRange(payloadStart, payloadEnd) to type
}

private fun findAvatarEnd(wire: String, boundary: String, payloadStart: Int): Int {
    val delimiter = "\r\n--$boundary"
    var start = payloadStart
    while (true) {
        val end = wire.indexOf(delimiter, start)
        if (end < 0) invalidAvatar()
        val suffix = end + delimiter.length
        // An ordinary delimiter introduces a second part. A closing delimiter must end
        // the request, with the usual terminal CRLF optional.
        if (wire.startsWith("\r\n", suffix)) invalidAvatar()
        if (wire.startsWith("--", suffix)) {
            val next = suffix + 2
            if (next == wire.length || next + 2 == wire.length && wire.startsWith("\r\n", next)) return end
            if (wire.startsWith("\r\n", next)) invalidAvatar()
        }
        start = suffix
    }
}

private fun avatarPartType(headerBlock: String): AvatarContentType {
    val headers = mutableMapOf<String, String>()
    for (line in headerBlock.split("\r\n")) {
        val separator = line.indexOf(':')
        if (separator <= 0) invalidAvatar()
        val name = line.substring(0, separator)
        val value = line.substring(separator + 1)
        if (!headerName.matches(name) || value.any { it.code < 32 && it != '\t' || it.code == 127 }) invalidAvatar()
        if (headers.put(name.lowercase(), value.trim(' ', '\t')) != null) invalidAvatar()
    }
    if ("content-transfer-encoding" in headers) invalidAvatar()
    val rawDisposition = headers["content-disposition"] ?: invalidAvatar()
    val (disposition, parameters) = parseAvatarHeader(rawDisposition)
    if (!disposition.equals("form-data", ignoreCase = true) || parameters["name"] != "file") {
        invalidAvatar()
    }
    val rawType = headers["content-type"] ?: invalidAvatar()
    val (type) = parseAvatarHeader(rawType)
    return AvatarContentType.entries.firstOrNull { it.mimeType.equals(type, ignoreCase = true) } ?: invalidAvatar()
}

private fun parseAvatarHeader(raw: String): Pair<String, Map<String, String>> {
    if (raw.length > MAX_PART_HEADERS_BYTES) invalidAvatar()
    val firstParameter = raw.indexOf(';').let { if (it < 0) raw.length else it }
    val value = raw.substring(0, firstParameter).trim(' ', '\t')
    val parameters = mutableMapOf<String, String>()
    var offset = firstParameter
    // Consume complete parameters in order. Each name is unique and every quoted value is closed.
    while (offset < raw.length) {
        val match = headerParameter.matchAt(raw, offset) ?: invalidAvatar()
        val name = match.groupValues[1].lowercase()
        val parameter = match.groups[2]?.value ?: quotedPair.replace(match.groupValues[3]) { it.groupValues[1] }
        if (parameters.put(name, parameter) != null) invalidAvatar()
        offset = match.range.last + 1
    }
    return value to parameters
}

private fun avatarTooLarge(): Nothing = throw ApiException(
    HttpStatusCode.PayloadTooLarge, "INVALID_FIELD", "Фотография должна быть не больше 5 МиБ",
)

private fun invalidAvatar(): Nothing = throw ApiException(
    HttpStatusCode.UnprocessableEntity, "INVALID_FIELD", "Выберите корректное изображение JPEG или PNG",
)

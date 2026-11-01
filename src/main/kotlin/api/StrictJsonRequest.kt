package com.example.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.utils.io.readAvailable
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

private const val MAX_JSON_BODY_BYTES = 64 * 1024

/** Use for JSON request DTOs so syntax errors and invalid fields remain distinct. */
suspend fun <T> ApplicationCall.receiveStrictJson(deserializer: DeserializationStrategy<T>): T {
    val body = receiveBoundedJsonBody()
    val element = try {
        transportJson.parseToJsonElement(body)
    } catch (_: SerializationException) {
        throw ApiException(HttpStatusCode.BadRequest, "INVALID_JSON", "Некорректный JSON")
    }

    return try {
        transportJson.decodeFromJsonElement(deserializer, element)
    } catch (_: SerializationException) {
        // This includes unknown keys, missing required fields and invalid field types.
        throw ApiException(HttpStatusCode.UnprocessableEntity, "INVALID_FIELD", "Некорректные данные")
    }
}

private suspend fun ApplicationCall.receiveBoundedJsonBody(): String {
    val channel = receiveChannel()
    val bytes = ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    while (true) {
        // Read at most one byte beyond the limit so an untrusted body is never buffered whole.
        val count = channel.readAvailable(buffer, 0, minOf(buffer.size, MAX_JSON_BODY_BYTES + 1 - bytes.size()))
        if (count == -1) break
        bytes.write(buffer, 0, count)
        if (bytes.size() > MAX_JSON_BODY_BYTES) {
            throw ApiException(HttpStatusCode.PayloadTooLarge, "INVALID_FIELD", "Слишком большой запрос")
        }
    }

    return try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes.toByteArray()))
            .toString()
    } catch (_: CharacterCodingException) {
        throw ApiException(HttpStatusCode.BadRequest, "INVALID_JSON", "Некорректный JSON")
    }
}

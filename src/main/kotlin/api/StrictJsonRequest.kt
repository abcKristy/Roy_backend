package com.example.api

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveChannel
import io.ktor.utils.io.readAvailable
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

private const val MAX_JSON_BODY_BYTES = 64 * 1024

/** Use for explicit confirmations so that quoted values cannot authorize an action. */
internal object StrictJsonBooleanSerializer : KSerializer<Boolean> {
    override val descriptor = PrimitiveSerialDescriptor("StrictJsonBoolean", PrimitiveKind.BOOLEAN)

    override fun deserialize(decoder: Decoder): Boolean {
        val value = (decoder as? JsonDecoder)?.decodeJsonElement() as? JsonPrimitive
            ?: throw SerializationException("Expected JSON Boolean")
        if (value.isString) throw SerializationException("Expected JSON Boolean")
        return value.booleanOrNull ?: throw SerializationException("Expected JSON Boolean")
    }

    override fun serialize(encoder: Encoder, value: Boolean) = encoder.encodeBoolean(value)
}

/** Use for JSON request DTOs so syntax errors and invalid fields remain distinct. */
suspend fun <T> ApplicationCall.receiveStrictJson(deserializer: DeserializationStrategy<T>): T {
    val contentType = try {
        request.contentType()
    } catch (_: IllegalArgumentException) {
        ContentType.Application.OctetStream
    }
    val charset = contentType.parameter("charset")
    if (!contentType.withoutParameters().match(ContentType.Application.Json) ||
        (charset != null && !charset.equals("utf-8", ignoreCase = true))
    ) {
        throw ApiException(HttpStatusCode.UnsupportedMediaType, "UNSUPPORTED_MEDIA_TYPE", "Ожидается application/json в UTF-8")
    }
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

package com.example.api

import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.util.AttributeKey
import java.util.UUID

private val requestIdKey = AttributeKey<String>("request-id")

val RequestId = createApplicationPlugin(name = "RequestId") {
    onCall { call ->
        val supplied = call.request.headers["X-Request-Id"]
        val requestId = supplied?.takeIf(::isCanonicalUuid) ?: UUID.randomUUID().toString()
        call.attributes.put(requestIdKey, requestId)
        call.response.headers.append("X-Request-Id", requestId)
    }
}

fun ApplicationCall.requestId(): String = attributes[requestIdKey]

private fun isCanonicalUuid(value: String): Boolean =
    value.length == 36 && runCatching { UUID.fromString(value).toString().equals(value, ignoreCase = true) }
        .getOrDefault(false)

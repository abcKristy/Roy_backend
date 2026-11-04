package com.example.api

import io.ktor.http.ContentType
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.http.content.staticResources
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The reviewed, versioned document is served unchanged apart from the deployment origin. */
fun Route.documentationRoutes(publicBaseUrl: String? = null) {
    fun resource(name: String): String = requireNotNull(
        object {}.javaClass.classLoader.getResourceAsStream("api-docs/$name"),
    ) { "Missing bundled API documentation: $name" }.bufferedReader().use { it.readText() }

    val source = Json.parseToJsonElement(resource("openapi.json")) as JsonObject
    val specification = if (publicBaseUrl == null) source.toString() else JsonObject(source + (
        "servers" to JsonArray(listOf(JsonObject(mapOf("url" to JsonPrimitive(publicBaseUrl.trimEnd('/'))))))
    )).toString()
    val index = resource("index.html")
    get("/docs") { call.respondText(index, ContentType.Text.Html) }
    get("/docs/") { call.respondText(index, ContentType.Text.Html) }
    get("/openapi.json") { call.respondText(specification, ContentType.Application.Json) }
    get("/docs/websocket") { call.respondText(resource("websocket-api.md"), ContentType.Text.Plain) }
    get("/docs/contract-differences") {
        call.respondText(resource("api-contract-differences.md"), ContentType.Text.Plain)
    }
    staticResources("/docs/assets", "META-INF/resources/webjars/swagger-ui")
}

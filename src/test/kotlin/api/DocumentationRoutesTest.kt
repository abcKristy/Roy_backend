package com.example.api

import com.example.accounts.AccountDeletionService
import com.example.auth.*
import com.example.chats.ChatReadService
import com.example.chats.ChatSendService
import com.example.chats.MessageContentPolicy
import com.example.devices.DeviceService
import com.example.devices.DeviceTokenCipher
import com.example.geocoding.ReverseGeocodeResult
import com.example.geocoding.ReverseGeocoder
import com.example.meetings.BookingService
import com.example.meetings.ProposalService
import com.example.moderation.ModerationService
import com.example.notifications.NotificationService
import com.example.persistence.DatabaseRuntime
import com.example.persistence.JdbcLoginRepository
import com.example.persistence.JdbcRegistrationRepository
import com.example.persistence.JdbcSessionRepository
import com.example.points.PointReadRepository
import com.example.points.PointWriteService
import com.example.realtime.RealtimeHub
import com.example.realtime.installRealtimeTransport
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.routing.HttpMethodRouteSelector
import io.ktor.server.routing.PathSegmentConstantRouteSelector
import io.ktor.server.routing.PathSegmentParameterRouteSelector
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.*
import java.sql.Connection
import java.time.Instant
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** These checks start every route without a PostgreSQL instance or external provider. */
class DocumentationRoutesTest {
    @Test
    fun `published specification exactly covers the installed production REST route tree`() = testApplication {
        var installed = emptySet<String>()
        application {
            configureFixture()
            installed = restOperations(routing {})
        }
        val response = client.get("/openapi.json")
        assertEquals(HttpStatusCode.OK, response.status)
        val spec = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("3.1.0", spec.getValue("openapi").jsonPrimitive.content)
        val methods = setOf("get", "post", "put", "patch", "delete", "head", "options", "trace")
        val documented = spec.getValue("paths").jsonObject.flatMap { (path, item) ->
            item.jsonObject.keys.filter { it in methods }.map { "${it.uppercase()} $path" }
        }.toSet()
        assertTrue(installed.isNotEmpty(), "No installed routes were inspected")
        assertEquals(installed, documented,
            "OpenAPI drift: undocumented=${installed - documented}; phantom=${documented - installed}")
        assertFalse(documented.any { it.endsWith(" /api/v1/ws") }, "WebSocket must have a separate contract")
    }

    @Test
    fun `Swagger UI its bundled assets and separate contracts are public`() = testApplication {
        application { configureFixture() }
        val response = client.get("/docs")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(ContentType.Text.Html, response.contentType()?.withoutParameters())
        val html = response.bodyAsText()
        assertTrue(html.contains("SwaggerUIBundle"))
        assertTrue(html.contains("docExpansion: \"none\""), "Thematic groups should initially be collapsed")
        assertTrue(html.contains("/openapi.json"))
        assertFalse(html.contains("@SWAGGER_UI_VERSION@"), "Resource version must be expanded during build")
        val assets = Regex("(?:href|src)=\"(/docs/assets/[^\"]+)\"")
            .findAll(html).map { it.groupValues[1] }.toList()
        assertTrue(assets.any { it.endsWith("swagger-ui.css") })
        assertTrue(assets.any { it.endsWith("swagger-ui-bundle.js") })
        assets.forEach { asset ->
            val loaded = client.get(asset)
            assertEquals(HttpStatusCode.OK, loaded.status, "Bundled Swagger asset missing: $asset")
            assertTrue(loaded.bodyAsText().length > 100, "Swagger asset is empty: $asset")
        }
        assertEquals(html, client.get("/docs/").bodyAsText())
        listOf("/docs/websocket", "/docs/contract-differences").forEach { path ->
            val contract = client.get(path)
            assertEquals(HttpStatusCode.OK, contract.status, path)
            assertTrue(contract.bodyAsText().isNotBlank(), path)
        }
        val spec = Json.parseToJsonElement(client.get("/openapi.json").bodyAsText()).jsonObject
        val groupNames = spec.getValue("tags").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content }
        assertEquals(listOf("Авторизация", "Профиль", "Уведомления", "Карта и точки", "Темы и членство",
            "Чаты", "Сообщения", "Встречи и бронирования", "Жалобы", "Правовая информация", "Служебные"), groupNames)
        spec.getValue("paths").jsonObject.values.forEach { path ->
            path.jsonObject.values.forEach { operation ->
                val groups = operation.jsonObject.getValue("tags").jsonArray
                assertEquals(1, groups.size, "Each contract must appear in a single thematic block")
                assertTrue(groups.single().jsonPrimitive.content in groupNames)
            }
        }
        assertEquals("/", spec.getValue("servers").jsonArray.first().jsonObject.getValue("url").jsonPrimitive.content)
        val bearer = spec.getValue("components").jsonObject.getValue("securitySchemes").jsonObject
            .getValue("bearerAuth").jsonObject
        assertEquals("http", bearer.getValue("type").jsonPrimitive.content)
        assertEquals("bearer", bearer.getValue("scheme").jsonPrimitive.content)
    }

    @Test
    fun `configured public base URL is used for deployed Try it out requests`() = testApplication {
        application { configureFixture(publicBaseUrl = "https://api.example.invalid/") }
        val spec = Json.parseToJsonElement(client.get("/openapi.json").bodyAsText()).jsonObject
        assertEquals("https://api.example.invalid",
            spec.getValue("servers").jsonArray.single().jsonObject.getValue("url").jsonPrimitive.content)
    }

    @Test
    fun `unsupported Accept returns an empty 406 for JSON success and JSON errors`() = testApplication {
        application { configureFixture() }
        val spec = Json.parseToJsonElement(client.get("/openapi.json").bodyAsText()).jsonObject
        listOf("/health", "/api/v1/me").forEach { path ->
            val response = client.get(path) { header("Accept", "application/xml") }
            assertEquals(HttpStatusCode.NotAcceptable, response.status)
            assertEquals("", response.bodyAsText())
            val contract = spec.getValue("paths").jsonObject.getValue(path).jsonObject
                .getValue("get").jsonObject.getValue("responses").jsonObject.getValue("406").jsonObject
            assertFalse("content" in contract, "406 does not carry the JSON error envelope")
        }
    }

    @Test
    fun `live public and safe error responses match published examples`() = testApplication {
        val database = NoJdbcDatabase()
        application { configureFixture(database) }
        val spec = Json.parseToJsonElement(client.get("/openapi.json").bodyAsText()).jsonObject
        val health = client.get("/health")
        assertEquals(HttpStatusCode.OK, health.status)
        assertMatchesPublishedExample(spec, "/health", "get", health)
        assertMatchesPublishedExample(spec, "/", "get", client.get("/"))
        database.ready = false
        val unavailable = client.get("/health")
        assertEquals(HttpStatusCode.ServiceUnavailable, unavailable.status)
        assertMatchesPublishedExample(spec, "/health", "get", unavailable)
        val unauthenticated = client.get("/api/v1/me")
        assertEquals(HttpStatusCode.Unauthorized, unauthenticated.status)
        assertMatchesPublishedExample(spec, "/api/v1/me", "get", unauthenticated)
        val malformed = client.post("/api/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("{")
        }
        assertEquals(HttpStatusCode.BadRequest, malformed.status)
        assertMatchesPublishedExample(spec, "/api/v1/auth/login", "post", malformed)
        val unknownField = client.post("/api/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"phone":"+12025550123","password":"Synthetic-only-123","unknown":true}""")
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, unknownField.status)
        assertMatchesPublishedExample(spec, "/api/v1/auth/login", "post", unknownField)
    }

    private suspend fun assertMatchesPublishedExample(spec: JsonObject, path: String, method: String, response: HttpResponse) {
        val declared = spec.getValue("paths").jsonObject.getValue(path).jsonObject.getValue(method).jsonObject
            .getValue("responses").jsonObject.getValue(response.status.value.toString()).jsonObject
        val media = declared.getValue("content").jsonObject
        val actual = response.bodyAsText()
        val json = response.contentType()?.withoutParameters() == ContentType.Application.Json
        val content = media.entries.firstOrNull { (type, _) -> if (json) type == "application/json" else type.startsWith("text/") }
            ?.value?.jsonObject ?: error("No declared content for ${response.contentType()} at $method $path")
        val examples = content["examples"]?.jsonObject?.values?.map { it.jsonObject.getValue("value") }
            ?: listOfNotNull(content["example"])
        assertTrue(examples.isNotEmpty(), "Missing examples for $method $path ${response.status.value}")
        if (json) {
            fun comparable(value: JsonElement): JsonElement = if (value is JsonObject && "requestId" in value) {
                JsonObject(value - "requestId")
            } else value
            val actualJson = Json.parseToJsonElement(actual)
            if (actualJson is JsonObject && "error" in actualJson) {
                assertTrue(actualJson.getValue("requestId").jsonPrimitive.content.isNotBlank())
            }
            assertTrue(examples.any { comparable(it) == comparable(actualJson) },
                "Live response differs from every documented example: $method $path ${response.status.value}: $actual")
        } else {
            assertTrue(examples.any { it.jsonPrimitive.content == actual }, "Text response differs from documented examples")
        }
    }

    private fun restOperations(root: RoutingNode): Set<String> = buildSet {
        fun visit(node: RoutingNode, parts: List<String>, method: String?) {
            val selector = node.selector
            val nextParts = when (selector) {
                is PathSegmentConstantRouteSelector -> parts + selector.value
                is PathSegmentParameterRouteSelector -> parts + "{${selector.name}}"
                else -> parts
            }
            val path = "/" + nextParts.filter { it.isNotEmpty() }.joinToString("/")
            if (path == "/docs" || path.startsWith("/docs/") || path == "/openapi.json" || path == "/api/v1/ws") return
            val nextMethod = if (selector is HttpMethodRouteSelector) selector.method.value else method
            if (node.hasHandler() && nextMethod != null) add("$nextMethod $path")
            node.children.forEach { visit(it, nextParts, nextMethod) }
        }
        visit(root, emptyList(), null)
    }

    private fun Application.configureFixture(database: NoJdbcDatabase = NoJdbcDatabase(), publicBaseUrl: String? = null) {
        val secret = "documentation-test-secret-at-least-thirty-two-bytes"
        val hashing = PasswordHashingWork(object : PasswordHasher {
            override fun hash(password: String): String = error("Hashing must not run in documentation tests")
            override fun verify(password: String, encodedHash: String): Boolean = error("Hashing must not run")
            override fun needsRehash(encodedHash: String): Boolean = false
            override fun verifyUnknown(password: String) = error("Hashing must not run")
        })
        val issuer = JwtTokenIssuer(secret)
        val sessions = SessionAccessService(secret, object : SessionRepository {
            override suspend fun findActive(identity: SessionIdentity, now: Instant): UserSelf? = error("Session lookup must not run")
            override suspend fun revoke(identity: SessionIdentity, now: Instant): Boolean = error("Session mutation must not run")
        })
        configureTransport()
        installRealtimeTransport()
        configureRouting(
            database = database,
            registrationService = RegistrationService(UnpublishedPrivacyPolicy, hashing, JdbcRegistrationRepository(database), issuer),
            loginService = LoginService(JdbcLoginRepository(database), hashing, issuer),
            refreshService = RefreshService(JdbcSessionRepository(database), issuer),
            sessionAccess = sessions,
            pointReadRepository = PointReadRepository(database, secret),
            pointWriteService = PointWriteService(database),
            moderationService = ModerationService(database),
            chatReadService = ChatReadService(database, secret),
            chatSendService = ChatSendService(database, MessageContentPolicy()),
            proposalService = ProposalService(database, secret),
            bookingService = BookingService(database),
            notificationService = NotificationService(database, secret),
            deviceService = DeviceService(database, DeviceTokenCipher.fromBase64(Base64.getEncoder().encodeToString(ByteArray(32) { 7 }))),
            profileService = ProfileService(database, hashing),
            accountDeletionService = AccountDeletionService(database, hashing),
            realtimeHub = RealtimeHub(database, sessions),
            reverseGeocoder = object : ReverseGeocoder {
                override suspend fun lookup(position: com.example.points.PointPosition): ReverseGeocodeResult =
                    error("External lookup must not run")
            },
            documentationBaseUrl = publicBaseUrl,
        )
    }

    private class NoJdbcDatabase : DatabaseRuntime {
        var ready = true
        override suspend fun isReady(): Boolean = ready
        override suspend fun <T> withJdbcTransaction(block: (Connection) -> T): T = error("Documentation checks must not access a database")
        override suspend fun <T> withReadOnlyJdbcTransaction(block: (Connection) -> T): T = error("Documentation checks must not access a database")
        override fun close() = Unit
    }
}

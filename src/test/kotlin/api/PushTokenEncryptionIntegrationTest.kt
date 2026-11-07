package com.example.api

import com.example.auth.JwtTokenIssuer
import com.example.config.ServerConfig
import com.example.configuredModule
import com.example.devices.DeviceTokenCipher
import com.example.devices.fixtureKey
import com.example.devices.seedDeviceSession
import com.example.jobs.PushDeliveryWorker
import com.example.jobs.PushSendResult
import com.example.jobs.PushSender
import com.example.jobs.PushTokenDecoder
import com.example.persistence.HikariDatabaseBootstrap
import com.example.testing.testDatabaseUrl
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.runBlocking
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PushTokenEncryptionIntegrationTest {
    @Test
    fun `authenticated registration without a key is unavailable and does not store a device`() {
        val fixture = Fixture()
        val installation = UUID.randomUUID()
        val token = "fixture-${UUID.randomUUID()}"
        fixture.connection().use { connection ->
            val actor = connection.seedActor(fixture.jwtSecret)
            testApplication {
                environment { config = MapApplicationConfig() }
                application { configuredModule(fixture.config()) }
                assertEquals(HttpStatusCode.Unauthorized, client.register(installation, token).status)
                val response = client.register(installation, token, actor)
                assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
                val body = response.bodyAsText()
                assertEquals("PUSH_REGISTRATION_FAILED", body.errorCode())
                assertFalse(body.contains(token), "Registration failure must not expose the token")
                assertEquals(HttpStatusCode.NotFound, client.delete("/api/v1/me/push-devices/$installation") {
                    header(HttpHeaders.Authorization, actor.authorization)
                }.status)
            }
            connection.prepareStatement("SELECT 1 FROM device_registrations WHERE installation_id=?").use { statement ->
                statement.setObject(1, installation)
                statement.executeQuery().use { rows -> assertFalse(rows.next()) }
            }
        }
    }

    @Test
    fun `malformed key rejects application startup with a safe configuration error`() {
        val fixture = Fixture()
        val invalidKey = "invalid-${UUID.randomUUID()}!"
        val failure = assertFailsWith<IllegalArgumentException> {
            testApplication {
                environment { config = MapApplicationConfig() }
                application { configuredModule(fixture.config(invalidKey)) }
                startApplication()
            }
        }
        assertEquals("PUSH_TOKEN_ENCRYPTION_KEY_BASE64 is invalid", failure.message)
        assertFalse(failure.stackTraceToString().contains(invalidKey), "Configuration failure exposed its input")
    }

    @Test
    fun `configured registration stores ciphertext and digest with one stable result on retry`() {
        val fixture = Fixture()
        val config = fixture.config(fixtureKey())
        val installation = UUID.randomUUID()
        val token = "fixture-${UUID.randomUUID()}"
        fixture.connection().use { connection ->
            val actor = connection.seedActor(fixture.jwtSecret)
            val otherActor = connection.seedActor(fixture.jwtSecret)
            val conflictingInstallation = UUID.randomUUID()
            testApplication {
                environment { this.config = MapApplicationConfig() }
                application { configuredModule(config) }
                val initial = client.register(installation, token, actor)
                assertEquals(HttpStatusCode.OK, initial.status)
                val body = initial.bodyAsText()
                assertFalse(body.contains(token), "Registration response exposed the token")
                val registration = Json.parseToJsonElement(body).jsonObject
                assertEquals(setOf("installationId", "registeredAt"), registration.keys)
                assertEquals(installation.toString(), registration.getValue("installationId").jsonPrimitive.content)
                val stored = connection.readBinding(installation)
                assertEquals(actor.id, stored.owner)
                assertTrue(stored.ciphertext.startsWith("v1:"))
                assertFalse(stored.rowJson.contains(token), "Registration row contains the plaintext token")
                assertTrue(stored.digest == sha256(token), "Stored digest differs from SHA-256")
                val retry = client.register(installation, token, actor)
                assertEquals(HttpStatusCode.OK, retry.status)
                assertEquals(registration, Json.parseToJsonElement(retry.bodyAsText()).jsonObject)
                assertTrue(connection.readBinding(installation) == stored, "Retry changed the stored binding")
                val conflict = client.register(conflictingInstallation, token, otherActor)
                assertEquals(HttpStatusCode.Conflict, conflict.status)
                assertEquals("DEVICE_TOKEN_IN_USE", conflict.bodyAsText().errorCode())
                assertTrue(connection.readBinding(installation) == stored, "Conflict changed the owner's binding")
            }
        }
    }

    @Test
    fun `worker hands decrypted token to sender only with the correct key`() {
        val fixture = Fixture()
        val config = fixture.config(fixtureKey())
        val wrongKeyConfig = fixture.config(fixtureKey())
        val installation = UUID.randomUUID()
        val token = "fixture-${UUID.randomUUID()}"
        fixture.connection().use { connection ->
            val actor = connection.seedActor(fixture.jwtSecret)
            testApplication {
                environment { this.config = MapApplicationConfig() }
                application { configuredModule(config) }
                assertEquals(HttpStatusCode.OK, client.register(installation, token, actor).status)
            }
            val job = connection.seedDelivery(actor.id, installation)
            val original = connection.readBinding(installation)
            HikariDatabaseBootstrap(config).start().use { database ->
                runBlocking {
                    var sends = 0
                    var correctToken = false
                    val sender = PushSender { platform, receivedToken, _ ->
                        sends++
                        correctToken = platform == "ANDROID_RUSTORE" && receivedToken == token
                        PushSendResult.Delivered
                    }
                    val wrongKeyWorker = PushDeliveryWorker(database, sender, wrongKeyConfig.decoder())
                    assertEquals(1, wrongKeyWorker.processBatch())
                    assertEquals(0, sends)
                    assertEquals("FAILED" to "INVALID_TOKEN", connection.deliveryState(job))
                    assertTrue(connection.readBinding(installation) == original, "Decryption failure changed the binding")

                    val worker = PushDeliveryWorker(database, sender, config.decoder())
                    assertTrue(worker.retryFailed(job))
                    assertEquals(1, worker.processBatch())
                    assertEquals(1, sends)
                    assertTrue(correctToken, "Sender did not receive the original token for the registered platform")
                    assertEquals("SENT" to null, connection.deliveryState(job))
                }
            }
        }
    }

    private class Fixture {
        private val url = testDatabaseUrl()
        private val user = requireNotNull(System.getenv("DATABASE_USER"))
        private val password = requireNotNull(System.getenv("DATABASE_PASSWORD"))
        val jwtSecret = UUID.randomUUID().toString()

        init {
            require(Regex("^jdbc:postgresql://[^/]+/roy_m5t126_crypto$").matches(url))
        }

        fun connection(): Connection = DriverManager.getConnection(url, user, password)

        fun config(key: String? = null): ServerConfig = ServerConfig.fromEnvironment(buildMap {
            put("DATABASE_URL", url)
            put("DATABASE_USER", user)
            put("DATABASE_PASSWORD", password)
            put("JWT_SECRET", jwtSecret)
            put("APP_ENV", "production")
            key?.let { put("PUSH_TOKEN_ENCRYPTION_KEY_BASE64", it) }
        })
    }

    private class Actor(val id: UUID, val authorization: String)

    private data class Binding(val owner: UUID, val ciphertext: String, val digest: String, val rowJson: String)

    private fun Connection.readBinding(installation: UUID): Binding =
        prepareStatement(
            """SELECT d.user_id,d.token_ciphertext,d.token_digest,row_to_json(d)::text
               FROM device_registrations AS d WHERE d.installation_id=?""",
        ).use { statement ->
            statement.setObject(1, installation)
            statement.executeQuery().use { rows ->
                assertTrue(rows.next())
                Binding(rows.getObject(1, UUID::class.java), rows.getString(2), rows.getString(3), rows.getString(4))
                    .also { assertFalse(rows.next()) }
            }
        }

    private fun Connection.seedDelivery(user: UUID, installation: UUID): UUID {
        val event = UUID.randomUUID()
        val job = UUID.randomUUID()
        prepareStatement(
            """WITH point AS (
                   INSERT INTO points(id,location,address,address_source,created_by_user_id,created_at,updated_at)
                   VALUES (gen_random_uuid(),ST_SetSRID(ST_MakePoint(37.8,55.8),4326),'Fixture','MANUAL',?,
                           clock_timestamp(),clock_timestamp())
                   RETURNING id,created_by_user_id
               ), topic AS (
                   INSERT INTO point_topics(id,point_id,preset_key,created_at)
                   SELECT gen_random_uuid(),id,'football',clock_timestamp() FROM point RETURNING id,point_id
               ), chat AS (
                   INSERT INTO chats(id,point_topic_id,created_at)
                   SELECT gen_random_uuid(),id,clock_timestamp() FROM topic RETURNING id,point_topic_id
               ), member AS (
                   INSERT INTO chat_memberships(chat_id,user_id,joined_at)
                   SELECT chat.id,point.created_by_user_id,clock_timestamp() FROM chat
                   JOIN topic ON topic.id=chat.point_topic_id JOIN point ON point.id=topic.point_id
                   RETURNING chat_id,user_id
               ), prefs AS (
                   INSERT INTO notification_settings(user_id,updated_at)
                   SELECT user_id,clock_timestamp() FROM member
               ), event AS (
                   INSERT INTO notification_events(id,recipient_user_id,channel,event_type,dedupe_key,chat_id,
                       display_snapshot,visible_in_app,push_requested,created_at)
                   SELECT ?::uuid,user_id,'PERSONAL','chat_message',?,chat_id,'{}',true,true,clock_timestamp()
                   FROM member RETURNING id
               )
               INSERT INTO push_delivery_jobs(id,notification_event_id,installation_id,next_attempt_at)
               SELECT ?,id,?,clock_timestamp() FROM event""",
        ).use { statement ->
            statement.setObject(1, user)
            statement.setObject(2, event)
            statement.setString(3, event.toString())
            statement.setObject(4, job)
            statement.setObject(5, installation)
            assertEquals(1, statement.executeUpdate())
        }
        return job
    }

    private fun Connection.deliveryState(job: UUID): Pair<String, String?> =
        prepareStatement("SELECT status,last_error FROM push_delivery_jobs WHERE id=?").use { statement ->
            statement.setObject(1, job)
            statement.executeQuery().use { rows ->
                assertTrue(rows.next())
                rows.getString(1) to rows.getString(2)
            }
        }

    private fun Connection.seedActor(jwtSecret: String): Actor {
        val userId = UUID.randomUUID()
        val phone = "+7" + userId.mostSignificantBits.toULong().toString().takeLast(10).padStart(10, '0')
        prepareStatement(
            """INSERT INTO users (id,phone_e164,password_hash,display_name,age_years,
               accepted_privacy_version,accepted_privacy_at,created_at,updated_at)
               VALUES (?,?,'fixture','Fixture',20,'fixture',clock_timestamp(),clock_timestamp(),clock_timestamp())"""
        ).use { statement ->
            statement.setObject(1, userId)
            statement.setString(2, phone)
            assertEquals(1, statement.executeUpdate())
        }
        val session = seedDeviceSession(userId)
        val now = Instant.now()
        return Actor(userId, "Bearer " + JwtTokenIssuer(jwtSecret).issue(
            userId, session.sessionId, session.authVersion, now, now.plusSeconds(600)))
    }

    private suspend fun HttpClient.register(installation: UUID, token: String, actor: Actor? = null): HttpResponse =
        put("/api/v1/me/push-devices/$installation") {
            actor?.let { header(HttpHeaders.Authorization, it.authorization) }
            contentType(ContentType.Application.Json)
            setBody("""{"platform":"android","provider":"rustore","token":"$token"}""")
        }

    private fun String.errorCode(): String = Json.parseToJsonElement(this).jsonObject
        .getValue("error").jsonObject.getValue("code").jsonPrimitive.content

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun ServerConfig.decoder(): PushTokenDecoder {
        val cipher = DeviceTokenCipher.fromBase64(requireNotNull(pushTokenEncryptionKeyBase64))
        return PushTokenDecoder(cipher::decrypt)
    }
}

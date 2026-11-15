package com.example.api

import com.example.config.AppEnvironment
import com.example.config.ServerConfig
import com.example.persistence.HikariDatabaseBootstrap
import com.example.points.PointReadRepository
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLParameter
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.charset.StandardCharsets
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PointFilterCursorIntegrationTest {
    @Test
    fun `cursor continues stable map order only for the same canonical filter`() = testApplication {
        val url = com.example.testing.testDatabaseUrl("TEST_DATABASE_URL")
        require(Regex("^jdbc:postgresql://[^/]+/roy_m2t4_filter$").matches(url))
        val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER is required")
        val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD is required")
        HikariDatabaseBootstrap(ServerConfig(url, user, password,
            "cursor-test-secret", appEnvironment = AppEnvironment.PRODUCTION)).start().use { runtime ->
            application {
                configureTransport()
                configureRouting(runtime, pointReadRepository = PointReadRepository(runtime,
                    "cursor-filter-signing-secret-at-least-32-bytes"))
            }
            val bbox = "west=37.619&south=55.749&east=37.626&north=55.756"
            val first = client.get("/api/v1/points?$bbox&limit=1")
            assertEquals(HttpStatusCode.OK, first.status)
            val firstBody = Json.parseToJsonElement(first.bodyAsText()).jsonObject
            assertEquals(listOf("20000000-0000-4000-8000-000000000002"),
                firstBody.getValue("items").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content })
            val cursor = assertNotNull(firstBody.getValue("nextCursor").jsonPrimitive.content)

            // The cursor exposes only version, resource, filter hash, and public ordering keys.
            val payload = Base64.getUrlDecoder().decode(cursor.substringBefore('.')).toString(StandardCharsets.UTF_8)
            assertTrue(Regex("^1\\|points\\|[0-9a-f]{64}\\|[^|]+\\|[0-9a-f-]{36}$").matches(payload))
            assertTrue(!payload.contains("10000000-0000-4000-8000"))
            assertTrue(!payload.contains("+1202555010"))

            val second = client.get("/api/v1/points?$bbox&limit=1&peopleMin=0&peopleMax=50&cursor=$cursor")
            assertEquals(HttpStatusCode.OK, second.status)
            val secondBody = Json.parseToJsonElement(second.bodyAsText()).jsonObject
            assertEquals(listOf("20000000-0000-4000-8000-000000000001"),
                secondBody.getValue("items").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content })
            assertEquals(JsonNull, secondBody.getValue("nextCursor"))

            val changedFilters = listOf(
                "$bbox&limit=1&topicKey=football",
                "$bbox&limit=1&topicKey=other&customTopicLabel=%D0%A8%D0%B0%D1%85%D0%BC%D0%B0%D1%82%D1%8B",
                "$bbox&limit=1&peopleMin=1",
                "west=37.620&south=55.749&east=37.626&north=55.756&limit=1",
                "$bbox&limit=2",
            )
            for (query in changedFilters) {
                val response = client.get("/api/v1/points?$query&cursor=$cursor")
                assertEquals(HttpStatusCode.BadRequest, response.status, query)
                val error = Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("error").jsonObject
                assertEquals("INVALID_CURSOR", error.getValue("code").jsonPrimitive.content, query)
            }
            val tampered = (if (cursor.first() == 'A') 'B' else 'A') + cursor.drop(1)
            val bad = client.get("/api/v1/points?$bbox&limit=1&cursor=$tampered")
            assertEquals(HttpStatusCode.BadRequest, bad.status)
            assertEquals("INVALID_CURSOR", Json.parseToJsonElement(bad.bodyAsText()).jsonObject
                .getValue("error").jsonObject.getValue("code").jsonPrimitive.content)

            // Paginate a custom label whose Kotlin and database lowercasing can differ.
            val older = UUID.randomUUID()
            val newer = UUID.randomUUID()
            val exactLabel = "\u0130"
            val canonicalLabel = runtime.withJdbcTransaction { connection ->
                connection.prepareStatement(
                    """INSERT INTO points (id,location,address,address_source,created_at,updated_at)
                       VALUES (?,ST_SetSRID(ST_MakePoint(-155.64,22.14),4326),'Synthetic point','MANUAL',?,?)"""
                ).use { statement ->
                    val timestamp = OffsetDateTime.parse("2026-11-02T12:00:00Z")
                    for ((id, time) in listOf(older to timestamp, newer to timestamp.plusMinutes(1))) {
                        statement.setObject(1, id)
                        statement.setObject(2, time)
                        statement.setObject(3, time)
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }
                connection.prepareStatement(
                    """WITH topic AS (
                         INSERT INTO point_topics (id,point_id,preset_key,custom_label,created_at)
                         VALUES (gen_random_uuid(),?,'other',?,now()) RETURNING id
                       ) INSERT INTO chats (id,point_topic_id,created_at) SELECT gen_random_uuid(),id,now() FROM topic"""
                ).use { statement ->
                    for (id in listOf(older, newer)) {
                        statement.setObject(1, id)
                        statement.setString(2, exactLabel)
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }
                connection.prepareStatement("SELECT lower(btrim(?::text))").use { statement ->
                    statement.setString(1, exactLabel)
                    statement.executeQuery().use { rows ->
                        check(rows.next())
                        rows.getString(1)
                    }
                }
            }
            try {
                val params = "west=-156&south=22&east=-155&north=23&limit=1&topicKey=other"
                val exact = client.get("/api/v1/points?$params&customTopicLabel=${exactLabel.encodeURLParameter()}")
                assertEquals(HttpStatusCode.OK, exact.status)
                val exactBody = Json.parseToJsonElement(exact.bodyAsText()).jsonObject
                assertEquals(listOf(newer.toString()),
                    exactBody.getValue("items").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content })
                val unicodeCursor = exactBody.getValue("nextCursor").jsonPrimitive.content
                val equivalent = client.get(
                    "/api/v1/points?$params&customTopicLabel=${canonicalLabel.encodeURLParameter()}&cursor=$unicodeCursor"
                )
                assertEquals(HttpStatusCode.OK, equivalent.status)
                val equivalentBody = Json.parseToJsonElement(equivalent.bodyAsText()).jsonObject
                assertEquals(listOf(older.toString()),
                    equivalentBody.getValue("items").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content })
                assertEquals(JsonNull, equivalentBody.getValue("nextCursor"))
                val changed = client.get("/api/v1/points?$params&customTopicLabel=changed&cursor=$unicodeCursor")
                assertEquals(HttpStatusCode.BadRequest, changed.status)
            } finally {
                runtime.withJdbcTransaction { connection ->
                    connection.prepareStatement("DELETE FROM points WHERE id IN (?,?)").use { statement ->
                        statement.setObject(1, older)
                        statement.setObject(2, newer)
                        statement.executeUpdate()
                    }
                }
            }
        }
    }
}

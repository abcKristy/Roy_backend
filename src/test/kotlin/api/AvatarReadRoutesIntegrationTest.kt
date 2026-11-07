package com.example.api

import com.example.auth.Argon2idPasswordHasher
import com.example.auth.JwtTokenIssuer
import com.example.auth.PasswordHashingWork
import com.example.auth.ProfileService
import com.example.auth.SessionAccessService
import com.example.config.ServerConfig
import com.example.persistence.DatabaseRuntime
import com.example.persistence.HikariDatabaseBootstrap
import com.example.persistence.JdbcSessionRepository
import com.example.storage.AvatarContentType
import com.example.storage.AvatarImage
import com.example.storage.AvatarStore
import com.example.storage.LocalAvatarStore
import com.example.storage.restrictToOwner
import com.example.testing.testDatabaseUrl
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AvatarReadRoutesIntegrationTest {
    @Test
    fun `owner and active peer get the same bytes and validators without writes`() = fixture {
        val before = snapshot()
        for ((format, type) in listOf("png" to AvatarContentType.PNG, "jpeg" to AvatarContentType.JPEG)) {
            val bytes = imageBytes(format)
            val key = store.save(AvatarImage(bytes, type))
            sql("UPDATE users SET avatar_object_key=? WHERE id=?", key, owner.id)
            val afterSetup = snapshot()
            val ownerResponse = getAvatar(owner)
            val peerResponse = getAvatar(peer)
            for (response in listOf(ownerResponse, peerResponse)) {
                assertEquals(200, response.status.value)
                assertEquals(type.mimeType, response.headers[HttpHeaders.ContentType])
                assertEquals("private, no-cache", response.headers[HttpHeaders.CacheControl])
                assertEquals("Authorization", response.headers[HttpHeaders.Vary])
                assertEquals("nosniff", response.headers["X-Content-Type-Options"])
                assertContentEquals(bytes, response.body<ByteArray>())
                assertFalse(response.headers.entries().any { key in it.value.joinToString() })
            }
            assertEquals(assertNotNull(ownerResponse.headers[HttpHeaders.ETag]), peerResponse.headers[HttpHeaders.ETag])
            assertEquals(afterSetup, snapshot())
        }
        assertNotEquals(before, snapshot())
        assertEquals(200, getAvatar(owner, target = owner.id.toString().uppercase()).status.value)
    }

    @Test
    fun `missing foreign and departed avatars are indistinguishable without storage reads`() = fixture {
        val before = snapshot()
        val absent = getAvatar(peer, target = UUID.randomUUID().toString()).assertError(404, "RESOURCE_GONE")
        val key = currentKey()
        for ((viewer, target) in listOf(
            peer to adjacent.id.toString(), peer to outsider.id.toString(), left to owner.id.toString(),
            outsider to owner.id.toString(), peer to deleted.id.toString(), peer to peer.id.toString(),
            peer to key, peer to "1-1-1-1-1",
        )) {
            assertEquals(absent, getAvatar(viewer, target, validator = "*").assertError(404, "RESOURCE_GONE"))
        }
        assertEquals(0, store.reads.get())
        assertEquals(before, snapshot())
        leave(owner)
        getAvatar(peer).assertError(404, "RESOURCE_GONE")
        assertEquals(0, store.reads.get())
        assertEquals(200, getAvatar(owner).status.value)
    }

    @Test
    fun `legacy access only sessions remain active until expiry`() = fixture {
        sql("UPDATE auth_sessions SET auth_version=1 WHERE id=?", peer.session)
        getAvatar(peer).assertError(401, "SESSION_EXPIRED")
        assertEquals(0, store.reads.get())
        sql("UPDATE auth_sessions SET auth_version=NULL,refresh_token_hash=NULL WHERE id=?", peer.session)
        assertEquals(200, getAvatar(peer).status.value)
    }

    @Test
    fun `authentication precedes lookup and a revoked session cannot revalidate`() = fixture {
        getAvatar(null, validator = "*").assertError(401, "AUTH_REQUIRED")
        getAvatar(null, authorization = "Bearer invalid", validator = "*").assertError(401, "SESSION_EXPIRED")
        getAvatar(peer, authorization = bearer(peer.copy(session = UUID.randomUUID()))).assertError(401, "SESSION_EXPIRED")
        assertEquals(0, store.reads.get())
        val tag = assertNotNull(getAvatar(peer).headers[HttpHeaders.ETag])
        revoke(peer)
        getAvatar(peer, validator = tag).assertError(401, "SESSION_EXPIRED")
        assertEquals(1, store.reads.get())
    }

    @Test
    fun `conditional GET requires current access and returns the new representation after a swap`() = fixture {
        val tag = assertNotNull(getAvatar(peer).headers[HttpHeaders.ETag])
        for (validator in listOf(tag, "W/$tag", "\"other,tag\", W/$tag", "*")) {
            val response = getAvatar(peer, validator = validator)
            assertEquals(304, response.status.value, "Expected the conditional response for $validator")
            assertContentEquals(byteArrayOf(), response.body<ByteArray>())
            assertNull(response.headers[HttpHeaders.ContentType])
            assertEquals(tag, response.headers[HttpHeaders.ETag])
            assertEquals("private, no-cache", response.headers[HttpHeaders.CacheControl])
            assertEquals("Authorization", response.headers[HttpHeaders.Vary])
        }
        for (validator in listOf("W/*", "$tag, malformed", tag.trim('"'))) {
            assertEquals(200, getAvatar(peer, validator = validator).status.value)
        }
        val replacement = imageBytes("jpeg")
        val key = store.save(AvatarImage(replacement, AvatarContentType.JPEG))
        sql("UPDATE users SET avatar_object_key=? WHERE id=?", key, owner.id)
        val changed = getAvatar(peer, validator = tag)
        assertEquals(200, changed.status.value)
        assertNotEquals(tag, assertNotNull(changed.headers[HttpHeaders.ETag]))
        assertContentEquals(replacement, changed.body<ByteArray>())
        leave(peer)
        getAvatar(peer, validator = changed.headers[HttpHeaders.ETag]).assertError(404, "RESOURCE_GONE")
    }

    @Test
    fun `missing object and storage fault have safe distinct responses for an authorized viewer`() = fixture {
        val key = currentKey()
        store.delete(key)
        getAvatar(owner, validator = "*").assertError(404, "RESOURCE_GONE")
        // The actual file adapter refuses to serve an invalid format marker.
        Files.write(directory.resolve(key), byteArrayOf(9, 9))
        val error = getAvatar(owner).assertError(503, "AVATAR_STORAGE_UNAVAILABLE")
        assertFalse(key in error.toString())
        assertFalse(directory.toString() in error.toString())
        val before = store.reads.get()
        getAvatar(outsider).assertError(404, "RESOURCE_GONE")
        assertEquals(before, store.reads.get())
    }

    @Test
    fun `viewer leaving during provider read cannot receive a cached or fresh representation`() = fixture {
        val tag = assertNotNull(getAvatar(peer).headers[HttpHeaders.ETag])
        pauseAfterProviderRead(tag) { leave(peer) }.assertError(404, "RESOURCE_GONE")
    }

    @Test
    fun `revocation during provider read rejects even a matching cached representation`() = fixture {
        val tag = assertNotNull(getAvatar(peer).headers[HttpHeaders.ETag])
        pauseAfterProviderRead(tag) { revoke(peer) }.assertError(401, "SESSION_EXPIRED")
    }

    @Test
    fun `a reference swapped during provider read cannot return the old image`() = fixture {
        val replacement = imageBytes("jpeg")
        val key = store.save(AvatarImage(replacement, AvatarContentType.JPEG))
        pauseAfterProviderRead { sql("UPDATE users SET avatar_object_key=? WHERE id=?", key, owner.id) }
            .assertError(404, "RESOURCE_GONE")
        store.afterRead = {}
        assertContentEquals(replacement, getAvatar(peer).body<ByteArray>())
    }

    private fun fixture(run: suspend Fixture.() -> Unit) = testApplication {
        val url = testDatabaseUrl()
        require(url.endsWith("/roy_m5t120_read"))
        val config = ServerConfig(
            url, System.getenv("DATABASE_USER") ?: error("DATABASE_USER required"),
            System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD required"), SECRET,
        )
        val directory = Files.createTempDirectory("avatar-read-test-").toRealPath()
        try {
            restrictToOwner(directory)
            HikariDatabaseBootstrap(config).start().use { database ->
                val store = ObservedStore(LocalAvatarStore(directory))
                application {
                    configureTransport()
                    configureRouting(
                        database,
                        sessionAccess = SessionAccessService(SECRET, JdbcSessionRepository(database)),
                        profileService = ProfileService(database, PasswordHashingWork(Argon2idPasswordHasher()), store),
                    )
                }
                Fixture(client, database, directory, store).apply { seed() }.run()
            }
        } finally {
            check(directory.toFile().deleteRecursively())
        }
    }

    private class Fixture(
        private val client: HttpClient,
        private val database: DatabaseRuntime,
        val directory: Path,
        val store: ObservedStore,
    ) {
        val owner = Actor()
        val peer = Actor()
        val adjacent = Actor()
        val outsider = Actor()
        val left = Actor()
        val deleted = Actor()
        private val point = UUID.randomUUID()
        private val sharedChat = UUID.randomUUID()
        private val adjacentChat = UUID.randomUUID()

        suspend fun seed() {
            val key = store.save(AvatarImage(imageBytes("png"), AvatarContentType.PNG))
            database.withJdbcTransaction { connection ->
                for (actor in listOf(owner, peer, adjacent, outsider, left)) {
                    connection.update(
                        """INSERT INTO users (id,phone_e164,password_hash,display_name,age_years,avatar_object_key,
                           accepted_privacy_version,accepted_privacy_at,created_at,updated_at)
                           VALUES (?,?,'test-hash','Avatar fixture',24,?,'test',now(),now(),now())""",
                        actor.id, "+7" + actor.id.mostSignificantBits.toULong().toString().takeLast(10).padStart(10, '0'),
                        key.takeIf { actor in listOf(owner, adjacent, outsider) },
                    )
                    connection.update(
                        """INSERT INTO auth_sessions (id,user_id,created_at,expires_at,auth_version,refresh_token_hash)
                           VALUES (?,?,now(),now()+INTERVAL '1 hour',0,?)""",
                        actor.session, actor.id, actor.session.toString().replace("-", "").repeat(2),
                    )
                }
                connection.update("INSERT INTO users (id,created_at,updated_at,deleted_at) VALUES (?,now(),now(),now())", deleted.id)
                connection.update(
                    """INSERT INTO points (id,location,address,address_source,created_by_user_id,created_at,updated_at)
                       VALUES (?,ST_SetSRID(ST_MakePoint(70,-20),4326),'Fixture','MANUAL',?,now(),now())""", point, owner.id,
                )
                for ((chat, label) in listOf(sharedChat to "shared", adjacentChat to "adjacent")) {
                    val topic = UUID.randomUUID()
                    connection.update(
                        "INSERT INTO point_topics (id,point_id,preset_key,custom_label,created_at) VALUES (?,?,'other',?,now())",
                        topic, point, label,
                    )
                    connection.update("INSERT INTO chats (id,point_topic_id,created_at) VALUES (?,?,now())", chat, topic)
                }
                for ((chat, actor) in listOf(sharedChat to owner, sharedChat to peer, sharedChat to left, adjacentChat to adjacent)) {
                    connection.update("INSERT INTO chat_memberships (chat_id,user_id,joined_at) VALUES (?,?,now())", chat, actor.id)
                }
                connection.update("UPDATE chat_memberships SET left_at=now() WHERE chat_id=? AND user_id=?", sharedChat, left.id)
            }
        }

        suspend fun getAvatar(
            viewer: Actor?,
            target: String = owner.id.toString(),
            validator: String? = null,
            authorization: String? = viewer?.let(::bearer),
        ) = client.get("/api/v1/users/$target/avatar") {
            authorization?.let { header(HttpHeaders.Authorization, it) }
            validator?.let { header(HttpHeaders.IfNoneMatch, it) }
        }

        suspend fun sql(statement: String, vararg values: Any?) =
            database.withJdbcTransaction { it.update(statement, *values) }

        suspend fun leave(actor: Actor) =
            sql("UPDATE chat_memberships SET left_at=clock_timestamp() WHERE chat_id=? AND user_id=?", sharedChat, actor.id)

        suspend fun revoke(actor: Actor) =
            sql("UPDATE auth_sessions SET revoked_at=clock_timestamp() WHERE id=?", actor.session)

        suspend fun currentKey(): String = database.withReadOnlyJdbcTransaction { connection ->
            connection.prepareStatement("SELECT avatar_object_key FROM users WHERE id=?").use {
                it.setObject(1, owner.id)
                it.executeQuery().use { rows -> check(rows.next()); rows.getString(1) }
            }
        }

        suspend fun snapshot(): List<String> = database.withReadOnlyJdbcTransaction { connection ->
            listOf("users", "auth_sessions", "chat_memberships", "media_deletion_jobs", "messages").map { table ->
                connection.createStatement().use {
                    it.executeQuery("SELECT COALESCE(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)), '[]')::text FROM $table t")
                        .use { rows -> check(rows.next()); rows.getString(1) }
                }
            }
        } + Files.list(directory).use { it.map { file -> file.fileName.toString() }.sorted().toList() }

        suspend fun pauseAfterProviderRead(validator: String? = null, mutate: suspend () -> Unit): HttpResponse =
            withTimeout(10_000) {
                coroutineScope {
                    val loaded = CompletableDeferred<Unit>()
                    val release = CompletableDeferred<Unit>()
                    store.afterRead = {
                        loaded.complete(Unit)
                        release.await()
                    }
                    val response = async { getAvatar(peer, validator = validator) }
                    try {
                        assertTrue(select {
                            loaded.onAwait { true }
                            response.onAwait { false }
                        }, "Request completed without reaching the provider read")
                        mutate()
                    } finally {
                        release.complete(Unit)
                    }
                    response.await()
                }
            }
    }

    private class ObservedStore(private val delegate: LocalAvatarStore) : AvatarStore by delegate {
        val reads = AtomicInteger()
        var afterRead: suspend () -> Unit = {}

        override suspend fun read(objectKey: String): AvatarImage? {
            reads.incrementAndGet()
            val image = delegate.read(objectKey)
            afterRead()
            return image
        }
    }

    private data class Actor(val id: UUID = UUID.randomUUID(), val session: UUID = UUID.randomUUID())

    companion object {
        private const val SECRET = "test-only-secret-at-least-thirty-two-bytes-long"

        private fun bearer(actor: Actor): String {
            val now = Instant.now()
            return "Bearer " + JwtTokenIssuer(SECRET).issue(actor.id, actor.session, 0, now.minusSeconds(1), now.plusSeconds(3600))
        }

        private fun imageBytes(format: String): ByteArray {
            val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
            image.setRGB(0, 0, 0x00ff00)
            return ByteArrayOutputStream().apply { check(ImageIO.write(image, format, this)) }.toByteArray()
        }

        private fun Connection.update(statement: String, vararg values: Any?) {
            prepareStatement(statement).use {
                values.forEachIndexed { index, value -> it.setObject(index + 1, value) }
                assertEquals(1, it.executeUpdate())
            }
        }

        private suspend fun HttpResponse.assertError(status: Int, code: String) =
            this.also { assertEquals(status, it.status.value) }.let {
                Json.parseToJsonElement(bodyAsText()).jsonObject.getValue("error").jsonObject
            }.also {
                assertEquals(code, it.getValue("code").jsonPrimitive.content)
                assertNull(headers[HttpHeaders.ETag])
                assertEquals("no-store", headers[HttpHeaders.CacheControl])
                assertEquals("Authorization", headers[HttpHeaders.Vary])
                assertTrue(headers[HttpHeaders.ContentType].orEmpty().startsWith("application/json"))
            }
    }
}

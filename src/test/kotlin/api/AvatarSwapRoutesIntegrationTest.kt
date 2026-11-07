package com.example.api

import com.example.auth.Argon2idPasswordHasher
import com.example.auth.AvatarUpdateResponse
import com.example.auth.JwtTokenIssuer
import com.example.auth.PasswordHashingWork
import com.example.auth.ProfileService
import com.example.auth.SessionAccessService
import com.example.config.ServerConfig
import com.example.persistence.DatabaseRuntime
import com.example.persistence.HikariDatabaseBootstrap
import com.example.persistence.JdbcSessionRepository
import com.example.storage.AvatarImage
import com.example.storage.AvatarStorageException
import com.example.storage.AvatarStore
import com.example.storage.LocalAvatarStore
import com.example.storage.restrictToOwner
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
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.call
import io.ktor.server.request.ApplicationReceivePipeline
import io.ktor.server.routing.routing
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeFully
import io.ktor.server.response.ApplicationSendPipeline
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.CRC32
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AvatarSwapRoutesIntegrationTest {
    @Test
    fun `same processed upload reuses current object and replacements queue each old key once`() = fixture {
        putAvatar(png(RED)).assertAvatar(user.id)
        val first = assertNotNull(key())
        assertEquals(RED, pixel(first))
        putAvatar(png(RED)).assertAvatar(user.id)
        assertEquals(first, key())
        assertEquals(emptyList(), jobs())

        putAvatar(png(GREEN)).assertAvatar(user.id)
        val second = assertNotNull(key())
        assertNotEquals(first, second)
        assertEquals(GREEN, pixel(second))
        putAvatar(png(BLUE)).assertAvatar(user.id)
        val third = assertNotNull(key())
        assertNotEquals(second, third)
        assertEquals(BLUE, pixel(third))
        assertEquals(setOf(first, second, third), files())
        assertEquals(listOf(first, second).sorted(), jobs())
        assertNull(key(other.id))
    }

    @Test
    fun `delete clears the pointer and queues one job even when repeated`() = fixture {
        putAvatar(png(RED)).assertAvatar(user.id)
        val old = assertNotNull(key())
        repeat(2) {
            val reply = deleteAvatar()
            assertEquals(204, reply.status.value)
            assertEquals("", reply.bodyAsText())
        }
        assertNull(key())
        assertEquals(listOf(old), jobs())
        assertEquals(RED, pixel(old))
    }

    @Test
    fun `rejected swap rolls back reference and old job and removes staged file`() = fixture {
        putAvatar(png(RED)).assertAvatar(user.id)
        val old = assertNotNull(key())
        database.withJdbcTransaction { connection ->
            connection.createStatement().use {
                it.execute(
                    """CREATE FUNCTION reject_fixture_avatar() RETURNS trigger LANGUAGE plpgsql AS
                       $$ BEGIN RAISE EXCEPTION 'fixture avatar write rejected'; END $$"""
                )
                it.execute(
                    """CREATE CONSTRAINT TRIGGER reject_fixture_avatar AFTER UPDATE OF avatar_object_key ON users
                       DEFERRABLE INITIALLY DEFERRED FOR EACH ROW WHEN (OLD.id = '${user.id}'::uuid) EXECUTE FUNCTION reject_fixture_avatar()"""
                )
            }
        }
        try {
            putAvatar(png(GREEN)).assertError(500, "INTERNAL_ERROR")
            assertEquals(old, key())
            assertEquals(RED, pixel(old))
            assertEquals(setOf(old), files())
            assertTrue(old !in jobs())
        } finally {
            database.withJdbcTransaction { connection ->
                connection.createStatement().use {
                    it.execute("DROP TRIGGER reject_fixture_avatar ON users")
                    it.execute("DROP FUNCTION reject_fixture_avatar()")
                }
            }
        }
    }

    @Test
    fun `provider failures before and after object write preserve the previous avatar`() = fixture {
        putAvatar(png(RED)).assertAvatar(user.id)
        val old = assertNotNull(key())

        for (failure in listOf(WriteFailure.BEFORE, WriteFailure.AFTER)) {
            store.writeFailure = failure
            putAvatar(png(GREEN)).assertError(503, "AVATAR_STORAGE_UNAVAILABLE")
            assertEquals(old, key())
            assertEquals(RED, pixel(old))
            assertEquals(setOf(old), files())
        }
        assertEquals(2, jobs().size)
        assertTrue(old !in jobs())
    }

    @Test
    fun `failed compensation leaves durable intent for the unbound object`() = fixture {
        putAvatar(png(RED)).assertAvatar(user.id)
        val old = assertNotNull(key())
        store.writeFailure = WriteFailure.AFTER
        store.deleteFailure = true
        putAvatar(png(GREEN)).assertError(503, "AVATAR_STORAGE_UNAVAILABLE")

        val unbound = jobs().single()
        assertNotEquals(old, unbound)
        assertEquals(old, key())
        assertEquals(setOf(old, unbound), files())
        assertEquals(GREEN, pixel(unbound))
    }

    @Test
    fun `mutation rejects absent wrong and revoked sessions`() = fixture {
        putAvatar(png(RED), authorization = null).assertError(401, "AUTH_REQUIRED")
        deleteAvatar(authorization = null).assertError(401, "AUTH_REQUIRED")
        val wrong = authorization(user.id, other.session)
        putAvatar(png(RED), authorization = wrong).assertError(401, "SESSION_EXPIRED")
        deleteAvatar(wrong).assertError(401, "SESSION_EXPIRED")
        revoke()
        putAvatar(png(RED)).assertError(401, "SESSION_EXPIRED")
        deleteAvatar().assertError(401, "SESSION_EXPIRED")
        assertNull(key())
        assertEquals(emptySet(), files())
        assertEquals(emptyList(), jobs())
    }

    @Test
    fun `legacy access session can upload an avatar`() = fixture {
        useLegacySession()

        putAvatar(png(RED)).assertAvatar(user.id)
        val stored = assertNotNull(key())
        assertEquals(RED, pixel(stored))
        assertEquals(setOf(stored), files())
        assertEquals(emptyList(), jobs())
    }

    @Test
    fun `legacy access session can delete an avatar`() = fixture {
        putAvatar(png(RED)).assertAvatar(user.id)
        val old = assertNotNull(key())
        useLegacySession()

        val reply = deleteAvatar()
        assertEquals(204, reply.status.value)
        assertEquals("", reply.bodyAsText())
        assertNull(key())
        assertEquals(listOf(old), jobs())
        assertEquals(RED, pixel(old))
    }

    @Test
    fun `upload rechecks the session after provider write`() = fixture {
        store.afterWrite = { revoke() }
        putAvatar(png(RED)).assertError(401, "SESSION_EXPIRED")
        assertNull(key())
        assertEquals(emptySet(), files())
    }

    @Test
    fun `older upload finishing storage late cannot overwrite a newer avatar`() = fixture {
        val staged = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        store.afterWrite = { image ->
            if (ImageIO.read(image.bytes.inputStream()).getRGB(0, 0) == RED) {
                staged.complete(Unit)
                resume.await()
            }
        }
        withTimeout(10_000) {
            coroutineScope {
                val older = async { putAvatar(png(RED)) }
                try {
                    staged.await()
                    putAvatar(png(GREEN)).assertAvatar(user.id)
                } finally {
                    resume.complete(Unit)
                }
                val error = older.await().assertError(409, "STALE_STATE")
                val details = error.getValue("details").jsonObject
                assertEquals(setOf("avatarUrl"), details.keys)
                assertEquals("/api/v1/users/${user.id}/avatar", details.getValue("avatarUrl").jsonPrimitive.content)
            }
        }
        val winner = assertNotNull(key())
        assertEquals(GREEN, pixel(winner))
        assertEquals(setOf(winner), files())
        assertTrue(winner !in jobs())
    }

    @Test
    fun `an older stalled multipart body cannot replace a newer committed avatar`() {
        val started = Channel<Unit>(Channel.UNLIMITED)
        fixture(onSlowReceive = { started.send(Unit) }) {
            val body = ByteChannel()
            withTimeout(10_000) {
                coroutineScope {
                    val older = async { putSlowAvatar(body) }
                    val winner = try {
                        started.receive()
                        putAvatar(png(GREEN)).assertAvatar(user.id)
                        assertNotNull(key())
                    } finally {
                        body.writeFully(multipart(png(RED), "image/png"))
                        body.close()
                    }
                    older.await().assertError(409, "STALE_STATE")
                    assertEquals(winner, key())
                    assertEquals(GREEN, pixel(winner))
                    assertEquals(setOf(winner), files())
                    assertEquals(emptyList(), jobs())
                }
            }
        }
    }

    @Test
    fun `stalled multipart bodies time out and release both upload permits`() {
        val started = Channel<Unit>(Channel.UNLIMITED)
        fixture(onSlowReceive = { started.send(Unit) }, bodyTimeoutMillis = 500) {
            val firstBody = ByteChannel()
            val secondBody = ByteChannel()
            withTimeout(10_000) {
                coroutineScope {
                    val first = async { putSlowAvatar(firstBody) }
                    val second = async { putSlowAvatar(secondBody) }
                    try {
                        repeat(2) { started.receive() }
                        val responses = assertNotNull(withTimeoutOrNull(2_500) { first.await() to second.await() })
                        responses.first.assertError(408, "REQUEST_TIMEOUT")
                        responses.second.assertError(408, "REQUEST_TIMEOUT")
                        putAvatar(png(GREEN)).assertAvatar(user.id)
                        assertEquals(GREEN, pixel(assertNotNull(key())))
                        assertEquals(emptyList(), jobs())
                    } finally {
                        firstBody.close()
                        secondBody.close()
                    }
                }
            }
        }
    }

    @Test
    fun `multipart byte limit accepts five MiB and rejects one extra byte`() = fixture {
        val image = png(RED)
        putAvatar(paddedPng(image, 5_242_880)).assertAvatar(user.id)
        val key = assertNotNull(key())
        putAvatar(paddedPng(image, 5_242_881)).assertError(413, "INVALID_FIELD")
        putAvatar(image, mediaType = "image/jpeg").assertError(422, "INVALID_FIELD")
        assertEquals(key, key())
        assertEquals(setOf(key), files())
        assertEquals(emptyList(), jobs())
    }

    @Test
    fun `a committed swap survives an error receiving commit confirmation`() = fixture {
        putAvatar(png(RED)).assertAvatar(user.id)
        val old = assertNotNull(key())
        store.afterWrite = { profileDatabase.failNextCommit.set(true) }

        putAvatar(png(GREEN)).assertError(500, "INTERNAL_ERROR")
        val committed = assertNotNull(key())
        assertNotEquals(old, committed)
        assertEquals(GREEN, pixel(committed))
        assertEquals(setOf(old, committed), files())
        assertEquals(listOf(old), jobs())
    }

    @Test
    fun `a staged object already due for cleanup cannot become current`() = fixture {
        putAvatar(png(RED)).assertAvatar(user.id)
        val old = assertNotNull(key())
        store.afterWrite = {
            database.withJdbcTransaction { connection ->
                connection.prepareStatement(
                    "UPDATE media_deletion_jobs SET next_attempt_at=clock_timestamp() WHERE id=?"
                ).use {
                    it.setObject(1, UUID.fromString((files() - old).single()))
                    assertEquals(1, it.executeUpdate())
                }
            }
        }

        putAvatar(png(GREEN)).assertError(503, "AVATAR_STORAGE_UNAVAILABLE")
        assertEquals(old, key())
        assertEquals(setOf(old), files())
        assertEquals(emptyList(), jobs())
    }

    @Test
    fun `an earlier cleanup claim prevents publication after the worker schedules a retry`() =
        rejectRescheduledStage(expireOriginalDeadline = false, resetAttempts = false)

    @Test
    fun `resetting attempts cannot extend the original staging deadline`() =
        rejectRescheduledStage(expireOriginalDeadline = true, resetAttempts = true)

    private fun rejectRescheduledStage(expireOriginalDeadline: Boolean, resetAttempts: Boolean) = fixture {
        putAvatar(png(RED)).assertAvatar(user.id)
        val old = assertNotNull(key())
        store.afterWrite = {
            val staged = (files() - old).single()
            val id = UUID.fromString(staged)
            database.withJdbcTransaction { connection ->
                connection.prepareStatement(
                    """UPDATE media_deletion_jobs
                       SET created_at=CASE WHEN ? THEN clock_timestamp()-INTERVAL '16 minutes' ELSE created_at END,
                           next_attempt_at=clock_timestamp()
                       WHERE id=? AND status='PENDING'"""
                ).use {
                    it.setBoolean(1, expireOriginalDeadline)
                    it.setObject(2, id)
                    assertEquals(1, it.executeUpdate())
                }
                connection.prepareStatement(
                    """UPDATE media_deletion_jobs
                       SET status='PROCESSING',attempts=attempts+1,claim_token=gen_random_uuid(),
                           lease_until=clock_timestamp()+INTERVAL '45 seconds'
                       WHERE id=? AND status='PENDING' AND next_attempt_at<=clock_timestamp()"""
                ).use {
                    it.setObject(1, id)
                    assertEquals(1, it.executeUpdate())
                }
            }
            // A delete may take effect before its provider response is lost.
            store.delete(staged)
            database.withJdbcTransaction { connection ->
                connection.prepareStatement(
                    """UPDATE media_deletion_jobs
                       SET status='PENDING', attempts=CASE WHEN ? THEN 0 ELSE attempts END,
                           claim_token=NULL, lease_until=NULL, next_attempt_at=clock_timestamp()+INTERVAL '1 hour'
                       WHERE id=? AND status='PROCESSING'"""
                ).use {
                    it.setBoolean(1, resetAttempts)
                    it.setObject(2, id)
                    assertEquals(1, it.executeUpdate())
                }
            }
        }

        putAvatar(png(GREEN)).assertError(503, "AVATAR_STORAGE_UNAVAILABLE")
        assertEquals(old, key())
        assertEquals(RED, pixel(old))
        assertEquals(setOf(old), files())
        assertTrue(old !in jobs())
    }

    @Test
    fun `delivering an earlier response after a new avatar commit preserves the new pointer`() {
        val delayed = AtomicBoolean()
        val ready = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture(beforeRespond = {
            if (delayed.compareAndSet(false, true)) {
                ready.complete(Unit)
                release.await()
            }
        }) {
            withTimeout(10_000) {
                coroutineScope {
                    val earlier = async { putAvatar(png(RED)) }
                    val laterKey = try {
                        ready.await()
                        assertEquals(RED, pixel(assertNotNull(key())))
                        putAvatar(png(GREEN)).assertAvatar(user.id)
                        assertNotNull(key())
                    } finally {
                        release.complete(Unit)
                    }
                    earlier.await().assertAvatar(user.id)
                    assertEquals(laterKey, key())
                    assertEquals(GREEN, pixel(laterKey))
                }
            }
        }
    }

    private fun fixture(
        beforeRespond: suspend () -> Unit = {},
        onSlowReceive: suspend () -> Unit = {},
        bodyTimeoutMillis: Long? = null,
        run: suspend Fixture.() -> Unit,
    ) = testApplication {
        val url = testDatabaseUrl()
        require(url.endsWith("/roy_m5t119_avatar"))
        val config = ServerConfig(
            url, System.getenv("DATABASE_USER") ?: error("DATABASE_USER required"),
            System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD required"), SECRET,
        )
        val directory = Files.createTempDirectory("avatar-swap-test-").toRealPath()
        try {
            restrictToOwner(directory)
            HikariDatabaseBootstrap(config).start().use { database ->
                val user = database.seed()
                val other = database.seed()
                val store = FaultStore(LocalAvatarStore(directory))
                val profileDatabase = CommitFaultDatabase(database)
                application {
                    receivePipeline.intercept(ApplicationReceivePipeline.Before) {
                        if (call.request.headers[SLOW_UPLOAD_HEADER] != null) onSlowReceive()
                    }
                    sendPipeline.intercept(ApplicationSendPipeline.Before) {
                        if (subject is AvatarUpdateResponse) beforeRespond()
                    }
                    configureTransport()
                    val sessions = SessionAccessService(SECRET, JdbcSessionRepository(database))
                    val profiles = ProfileService(profileDatabase, PasswordHashingWork(Argon2idPasswordHasher()), store)
                    if (bodyTimeoutMillis == null) {
                        configureRouting(database, sessionAccess = sessions, profileService = profiles)
                    } else {
                        // Keep the route and body receiver real while shrinking the deadline for the fixture.
                        routing { profileRoutes(profiles, sessions, null, avatarBodyTimeoutMillis = bodyTimeoutMillis) }
                    }
                }
                Fixture(client, database, profileDatabase, user, other, directory, store).apply {
                    existingJobs = jobs().toSet()
                }.run()
            }
        } finally {
            check(directory.toFile().deleteRecursively())
        }
    }

    private class Fixture(
        val client: HttpClient,
        val database: DatabaseRuntime,
        val profileDatabase: CommitFaultDatabase,
        val user: Actor,
        val other: Actor,
        val directory: Path,
        val store: FaultStore,
    ) {
        var existingJobs = emptySet<String>()

        suspend fun putAvatar(
            bytes: ByteArray,
            authorization: String? = authorization(user.id, user.session),
            mediaType: String = "image/png",
        ) = client.put(ROUTE) {
            authorization?.let { header(HttpHeaders.Authorization, it) }
            header(HttpHeaders.ContentType, "multipart/form-data; boundary=avatar-fixture")
            setBody(multipart(bytes, mediaType))
        }

        suspend fun putSlowAvatar(body: ByteReadChannel) = client.put(ROUTE) {
            header(HttpHeaders.Authorization, authorization(user.id, user.session))
            header(SLOW_UPLOAD_HEADER, "true")
            setBody(object : OutgoingContent.ReadChannelContent() {
                override val contentType = ContentType.parse("multipart/form-data; boundary=avatar-fixture")
                override fun readFrom(): ByteReadChannel = body
            })
        }

        suspend fun deleteAvatar(authorization: String? = authorization(user.id, user.session)) = client.delete(ROUTE) {
            authorization?.let { header(HttpHeaders.Authorization, it) }
        }

        suspend fun key(id: UUID = user.id): String? = database.withReadOnlyJdbcTransaction { connection ->
            connection.prepareStatement("SELECT avatar_object_key FROM users WHERE id=?").use {
                it.setObject(1, id)
                it.executeQuery().use { rows -> check(rows.next()); rows.getString(1) }
            }
        }

        suspend fun jobs(): List<String> = database.withReadOnlyJdbcTransaction { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT object_key FROM media_deletion_jobs WHERE status='PENDING' ORDER BY object_key")
                    .use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
            }.filterNot { it in existingJobs }
        }

        suspend fun useLegacySession() = database.withJdbcTransaction { connection ->
            connection.prepareStatement(
                "UPDATE auth_sessions SET auth_version=NULL,refresh_token_hash=NULL WHERE id=?"
            ).use {
                it.setObject(1, user.session)
                assertEquals(1, it.executeUpdate())
            }
        }

        suspend fun revoke() = database.withJdbcTransaction { connection ->
            connection.prepareStatement("UPDATE auth_sessions SET revoked_at=clock_timestamp() WHERE id=?").use {
                it.setObject(1, user.session)
                check(it.executeUpdate() == 1)
            }
        }

        suspend fun pixel(key: String) = ImageIO.read(assertNotNull(store.read(key)).bytes.inputStream()).getRGB(0, 0)

        fun files(): Set<String> = Files.list(directory).use { paths ->
            paths.map { it.fileName.toString() }.toList().toSet()
        }
    }

    private class CommitFaultDatabase(private val actual: DatabaseRuntime) : DatabaseRuntime by actual {
        val failNextCommit = AtomicBoolean()

        override suspend fun <T> withJdbcTransaction(block: (Connection) -> T): T {
            val result = actual.withJdbcTransaction(block)
            if (failNextCommit.compareAndSet(true, false)) throw SQLException("Fixture lost commit confirmation")
            return result
        }
    }

    private class FaultStore(private val actual: LocalAvatarStore) : AvatarStore by actual {
        var writeFailure: WriteFailure? = null
        var deleteFailure = false
        var afterWrite: suspend (AvatarImage) -> Unit = {}


        override suspend fun save(image: AvatarImage, objectId: UUID): String {
            if (writeFailure == WriteFailure.BEFORE) throw AvatarStorageException()
            val key = actual.save(image, objectId)

            if (writeFailure == WriteFailure.AFTER) throw AvatarStorageException()
            afterWrite(image)
            return key
        }

        override suspend fun delete(objectKey: String) {
            if (deleteFailure) throw AvatarStorageException()
            actual.delete(objectKey)
        }
    }

    private enum class WriteFailure { BEFORE, AFTER }

    private data class Actor(val id: UUID, val session: UUID)

    companion object {
        private const val ROUTE = "/api/v1/me/avatar"
        private const val SLOW_UPLOAD_HEADER = "X-Fixture-Slow-Upload"
        private const val SECRET = "test-only-secret-at-least-thirty-two-bytes-long"
        private const val RED = 0xffff0000.toInt()
        private const val GREEN = 0xff00ff00.toInt()
        private const val BLUE = 0xff0000ff.toInt()

        private fun authorization(user: UUID, session: UUID): String {
            val now = Instant.now()
            return "Bearer " + JwtTokenIssuer(SECRET).issue(user, session, 0, now.minusSeconds(1), now.plusSeconds(3600))
        }

        private suspend fun DatabaseRuntime.seed(): Actor = withJdbcTransaction { connection ->
            val actor = Actor(UUID.randomUUID(), UUID.randomUUID())
            connection.prepareStatement(
                """INSERT INTO users (id, phone_e164, password_hash, display_name, age_years,
                   accepted_privacy_version, accepted_privacy_at, created_at, updated_at)
                   VALUES (?, ?, 'test-hash', 'Аня', 24, 'test', clock_timestamp(), clock_timestamp(), clock_timestamp())"""
            ).use {
                it.setObject(1, actor.id)
                it.setString(2, "+7" + actor.id.mostSignificantBits.toULong().toString().takeLast(10).padStart(10, '0'))
                it.executeUpdate()
            }
            connection.prepareStatement(
                """INSERT INTO auth_sessions (id, user_id, created_at, expires_at, auth_version, refresh_token_hash)
                   VALUES (?, ?, clock_timestamp(), clock_timestamp() + INTERVAL '1 hour', 0, ?)"""
            ).use {
                it.setObject(1, actor.session)
                it.setObject(2, actor.id)
                it.setString(3, actor.session.toString().replace("-", "").repeat(2))
                it.executeUpdate()
            }
            actor
        }

        private fun png(pixel: Int): ByteArray {
            val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
            image.setRGB(0, 0, pixel)
            return ByteArrayOutputStream().apply { check(ImageIO.write(image, "png", this)) }.toByteArray()
        }

        private fun multipart(bytes: ByteArray, mediaType: String): ByteArray =
            ("--avatar-fixture\r\nContent-Disposition: form-data; name=\"file\"; filename=\"private.png\"\r\n" +
                "Content-Type: $mediaType\r\n\r\n").encodeToByteArray() + bytes +
                "\r\n--avatar-fixture--\r\n".encodeToByteArray()

        private fun paddedPng(png: ByteArray, size: Int): ByteArray {
            val data = "tEXtComment\u0000".encodeToByteArray() + ByteArray(size - png.size - 20) { 65 }
            val crc = CRC32().apply { update(data) }
            val chunk = ByteBuffer.allocate(data.size + 8).putInt(data.size - 4).put(data).putInt(crc.value.toInt())
            return png.copyOfRange(0, 33) + chunk.array() + png.copyOfRange(33, png.size)
        }

        private suspend fun HttpResponse.assertAvatar(id: UUID) {
            assertEquals(200, status.value)
            val response = Json.parseToJsonElement(bodyAsText()).jsonObject
            assertEquals(setOf("avatarUrl"), response.keys)
            assertEquals("/api/v1/users/$id/avatar", response.getValue("avatarUrl").jsonPrimitive.content)
        }

        private suspend fun HttpResponse.assertError(status: Int, code: String): JsonObject {
            assertEquals(status, this.status.value)
            val error = Json.parseToJsonElement(bodyAsText()).jsonObject.getValue("error").jsonObject
            assertEquals(code, error.getValue("code").jsonPrimitive.content)
            return error
        }
    }
}

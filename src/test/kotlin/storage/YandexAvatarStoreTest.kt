package com.example.storage

import com.example.config.AvatarObjectStorageConfig
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YandexAvatarStoreTest {
    @Test
    fun `signed private put and bounded get round trip with repeatable delete`() = runBlocking {
        val uploaded = AvatarImage(byteArrayOf(1, 2, 3), AvatarContentType.PNG)
        val requests = ConcurrentLinkedQueue<Pair<String, String>>()
        val putHeaders = ConcurrentLinkedQueue<Map<String, List<String>>>()
        val objects = mutableMapOf<String, ByteArray>()
        withFixture({ exchange ->
            val path = exchange.requestURI.path
            requests.add(exchange.requestMethod to path)
            when (exchange.requestMethod) {
                "PUT" -> {
                    putHeaders.add(exchange.requestHeaders.toMap())
                    objects[path] = exchange.requestBody.readAllBytes()
                    exchange.sendResponseHeaders(200, -1)
                }
                "GET" -> {
                    val bytes = objects[path]
                    if (bytes == null) {
                        exchange.respond(404, "<Error><Code>NoSuchKey</Code></Error>")
                    } else {
                        exchange.responseHeaders.set("Content-Type", "image/png")
                        exchange.respond(200, bytes)
                    }
                }
                "DELETE" -> {
                    objects.remove(path)
                    exchange.sendResponseHeaders(204, -1)
                }
            }
        }) { store ->
            val key = store.save(uploaded)
            assertEquals(4, UUID.fromString(key).version())
            val secondKey = store.save(uploaded)
            assertNotEquals(key, secondKey)
            val read = requireNotNull(store.read(key))
            assertEquals(AvatarContentType.PNG, read.contentType)
            assertContentEquals(uploaded.bytes, read.bytes)
            store.delete(key)
            store.delete(key)
            assertNull(store.read(key))
            assertContentEquals(uploaded.bytes, requireNotNull(store.read(secondKey)).bytes)
            assertEquals(listOf(
                "PUT" to "/private-test/$key",
                "PUT" to "/private-test/$secondKey",
                "GET" to "/private-test/$key",
                "DELETE" to "/private-test/$key",
                "DELETE" to "/private-test/$key",
                "GET" to "/private-test/$key",
                "GET" to "/private-test/$secondKey",
            ), requests.toList())
        }

        val headers = putHeaders.first().mapKeys { (name, _) -> name.lowercase() }
        assertEquals(listOf("*"), headers["if-none-match"])
        assertEquals(listOf("image/png"), headers["content-type"])
        assertEquals(listOf("aws:kms"), headers["x-amz-server-side-encryption"])
        assertEquals(listOf("kms-fixture"), headers["x-amz-server-side-encryption-aws-kms-key-id"])
        assertFalse("x-amz-acl" in headers)
        assertFalse(headers.keys.any { it.startsWith("x-amz-grant-") })
        val authorization = headers.getValue("authorization").single()
        assertTrue(authorization.startsWith("AWS4-HMAC-SHA256 Credential=fixture-id/"))
        assertTrue("/ru-central1/s3/aws4_request" in authorization)
        assertTrue("if-none-match" in authorization.substringAfter("SignedHeaders=").substringBefore(","))
    }

    @Test
    fun `provider errors fail safely without exposing details or becoming missing objects`() = runBlocking {
        for ((status, code) in listOf(
            403 to "AccessDenied",
            404 to "NoSuchBucket",
            412 to "PreconditionFailed",
        )) {
            withFixture({ exchange ->
                exchange.respond(status, "<Error><Code>$code</Code><Message>provider-detail</Message></Error>")
            }) { store ->
                val key = UUID.randomUUID().toString()
                for (operation in listOf<suspend () -> Unit>(
                    { store.save(AvatarImage(byteArrayOf(1), AvatarContentType.JPEG)) },
                    { store.read(key) },
                    { store.delete(key) },
                )) {
                    val failure = assertFailsWith<AvatarStorageException> { operation() }
                    assertEquals("Avatar storage unavailable", failure.message)
                    assertTrue(generateSequence<Throwable>(failure) { it.cause }.all { it is AvatarStorageException })
                    assertFalse("provider-detail" in failure.stackTraceToString())
                }
            }
        }
    }

    @Test
    fun `reads reject missing length, oversized objects and unexpected media types`() = runBlocking {
        withFixture({ exchange ->
            exchange.responseHeaders.set("Content-Type", "image/png")
            exchange.sendResponseHeaders(200, MAX_STORED_AVATAR_BYTES + 1L)
        }) { store ->
            assertFailsWith<AvatarStorageException> { store.read(UUID.randomUUID().toString()) }
        }
        withFixture({ exchange ->
            exchange.responseHeaders.set("Content-Type", "image/png")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.write(1)
        }) { store ->
            assertFailsWith<AvatarStorageException> { store.read(UUID.randomUUID().toString()) }
        }
        withFixture({ exchange ->
            exchange.responseHeaders.set("Content-Type", "image/gif")
            exchange.respond(200, byteArrayOf(1))
        }) { store ->
            assertFailsWith<AvatarStorageException> { store.read(UUID.randomUUID().toString()) }
        }
    }

    @Test
    fun `invalid keys never reach the provider`() = runBlocking {
        val requests = ConcurrentLinkedQueue<String>()
        withFixture({ exchange ->
            requests.add(exchange.requestMethod)
            exchange.sendResponseHeaders(500, -1)
        }) { store ->
            assertFailsWith<IllegalArgumentException> { store.read("../other-key") }
            assertFailsWith<IllegalArgumentException> { store.delete("another-user/avatar") }
        }
        assertTrue(requests.isEmpty())
    }

    private suspend fun withFixture(
        handle: (HttpExchange) -> Unit,
        test: suspend (AvatarStore) -> Unit,
    ) {
        val fixture = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        fixture.createContext("/") { exchange -> exchange.use(handle) }
        fixture.start()
        try {
            val config = AvatarObjectStorageConfig("private-test", "kms-fixture", "fixture-id", "fixture-secret")
            val endpoint = URI.create("http://127.0.0.1:${fixture.address.port}")
            YandexAvatarStore(config, YandexAvatarStore.createClient(config, endpoint)).use { store -> test(store) }
        } finally {
            fixture.stop(0)
        }
    }

    private fun HttpExchange.respond(status: Int, body: String) =
        respond(status, body.toByteArray())

    private fun HttpExchange.respond(status: Int, body: ByteArray) {
        sendResponseHeaders(status, body.size.toLong())
        responseBody.write(body)
    }
}

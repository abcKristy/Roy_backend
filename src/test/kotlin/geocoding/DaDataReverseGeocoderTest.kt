package com.example.geocoding

import com.example.points.PointPosition
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DaDataReverseGeocoderTest {
    @Test
    fun `provider receives coordinates and token and empty result permits manual address`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val authorization = AtomicReference<String>()
        val requestBody = AtomicReference<String>()
        val responseBody = AtomicReference("""{"suggestions":[{"value":"  г Москва, ул Сухонская, д 11  "}]}""")
        server.createContext("/reverse") { exchange ->
            authorization.set(exchange.requestHeaders.getFirst("Authorization"))
            requestBody.set(exchange.requestBody.bufferedReader().use { it.readText() })
            val bytes = responseBody.get().toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val geocoder = DaDataReverseGeocoder("test-key", URI("http://127.0.0.1:${server.address.port}/reverse"))
            assertEquals("г Москва, ул Сухонская, д 11",
                assertIs<ReverseGeocodeResult.Found>(geocoder.lookup(PointPosition(55.878, 37.653))).address)
            assertEquals("Token test-key", authorization.get())
            assertTrue(requestBody.get().contains("\"lat\":55.878"))
            assertTrue(requestBody.get().contains("\"lon\":37.653"))
            responseBody.set("""{"suggestions":[]}""")
            assertEquals(ReverseGeocodeResult.None, geocoder.lookup(PointPosition(55.878, 37.653)))
            assertEquals(ReverseGeocodeResult.Unavailable,
                DaDataReverseGeocoder(null, URI("http://127.0.0.1:${server.address.port}/reverse"))
                    .lookup(PointPosition(55.878, 37.653)))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `provider failures timeout and malformed key are unavailable`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/error") { exchange ->
            exchange.sendResponseHeaders(503, -1)
            exchange.close()
        }
        server.createContext("/slow") { exchange ->
            Thread.sleep(250)
            val bytes = """{"suggestions":[]}""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val position = PointPosition(55.878, 37.653)
            assertEquals(ReverseGeocodeResult.Unavailable,
                DaDataReverseGeocoder("test-key", URI("$base/error")).lookup(position))
            assertEquals(ReverseGeocodeResult.Unavailable,
                DaDataReverseGeocoder("test-key", URI("$base/slow"), requestTimeout = Duration.ofMillis(40))
                    .lookup(position))
            assertEquals(ReverseGeocodeResult.Unavailable,
                DaDataReverseGeocoder("bad\r\nkey", URI("$base/error")).lookup(position))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `process rate guard rejects thirty first call without provider request`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val calls = AtomicInteger()
        server.createContext("/rate") { exchange ->
            calls.incrementAndGet()
            val bytes = """{"suggestions":[]}""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val geocoder = DaDataReverseGeocoder("test-key",
                URI("http://127.0.0.1:${server.address.port}/rate"), nanoTime = { 0L })
            repeat(30) {
                assertEquals(ReverseGeocodeResult.None, geocoder.lookup(PointPosition(55.878, 37.653)))
            }
            assertEquals(ReverseGeocodeResult.Unavailable, geocoder.lookup(PointPosition(55.878, 37.653)))
            assertEquals(30, calls.get())
        } finally {
            server.stop(0)
        }
    }
}

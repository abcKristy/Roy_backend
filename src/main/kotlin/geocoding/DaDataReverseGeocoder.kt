package com.example.geocoding

import com.example.points.PointPosition
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** DaData's server-side geolocate API; the API key never reaches the client response. */
class DaDataReverseGeocoder(
    private val apiKey: String?,
    private val endpoint: URI = URI("https://suggestions.dadata.ru/suggestions/api/4_1/rs/geolocate/address"),
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
    private val requestTimeout: Duration = Duration.ofSeconds(3),
    private val nanoTime: () -> Long = System::nanoTime,
) : ReverseGeocoder {
    private val recentCalls = ArrayDeque<Long>()

    override suspend fun lookup(position: PointPosition): ReverseGeocodeResult {
        if (apiKey.isNullOrBlank()) return ReverseGeocodeResult.Unavailable
        if (!reserveProviderCall()) return ReverseGeocodeResult.Unavailable
        return withContext(Dispatchers.IO) {
            try {
                val body = buildJsonObject {
                    put("lat", position.latitude)
                    put("lon", position.longitude)
                    put("count", 1)
                }.toString()
                val request = HttpRequest.newBuilder(endpoint)
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("Authorization", "Token $apiKey")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() !in 200..299) return@withContext ReverseGeocodeResult.Unavailable
                val suggestions = Json.parseToJsonElement(response.body()).jsonObject["suggestions"]?.jsonArray
                    ?: return@withContext ReverseGeocodeResult.Unavailable
                val address = suggestions.firstOrNull()?.jsonObject?.get("value")?.jsonPrimitive?.content?.trim()
                if (address.isNullOrEmpty() || address.codePointCount(0, address.length) !in 1..300) {
                    ReverseGeocodeResult.None
                } else {
                    ReverseGeocodeResult.Found(address)
                }
            } catch (_: java.io.IOException) {
                ReverseGeocodeResult.Unavailable
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                ReverseGeocodeResult.Unavailable
            } catch (_: IllegalArgumentException) {
                ReverseGeocodeResult.Unavailable
            }
        }
    }

    /** Keep this process below DaData's documented 30 requests per second per IP. */
    private fun reserveProviderCall(): Boolean = synchronized(recentCalls) {
        val now = nanoTime()
        while (recentCalls.isNotEmpty() && now - recentCalls.first() >= 1_000_000_000L) {
            recentCalls.removeFirst()
        }
        if (recentCalls.size >= 30) false else {
            recentCalls.addLast(now)
            true
        }
    }
}

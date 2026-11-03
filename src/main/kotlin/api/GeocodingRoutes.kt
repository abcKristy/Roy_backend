package com.example.api

import com.example.auth.SessionAccessService
import com.example.geocoding.ReverseGeocodeResult
import com.example.geocoding.ReverseGeocoder
import com.example.points.PointPosition
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

@Serializable
private data class ReversePosition(val lat: Double, val lon: Double)

@Serializable
private data class ReverseRequest(val position: ReversePosition)

@Serializable
private data class ReverseResponse(val address: String?, val source: String)

internal fun Route.geocodingRoutes(geocoder: ReverseGeocoder, sessions: SessionAccessService) {
    post("/api/v1/geocoding/reverse") {
        sessions.currentUser(call.request.headers[HttpHeaders.Authorization])
        val input = call.receiveStrictJson(ReverseRequest.serializer())
        val position = PointPosition(input.position.lat, input.position.lon)
        if (!position.latitude.isFinite() || position.latitude !in -90.0..90.0 ||
            !position.longitude.isFinite() || position.longitude !in -180.0..180.0) {
            throw ApiException(HttpStatusCode.UnprocessableEntity, "INVALID_FIELD", "Некорректные координаты")
        }
        when (val result = geocoder.lookup(position)) {
            is ReverseGeocodeResult.Found -> call.respond(ReverseResponse(result.address, "provider"))
            ReverseGeocodeResult.None -> call.respond(ReverseResponse(null, "none"))
            ReverseGeocodeResult.Unavailable -> throw ApiException(
                HttpStatusCode.ServiceUnavailable, "GEOCODER_UNAVAILABLE", "Не удалось определить адрес",
            )
        }
    }
}

package com.example.geocoding

import com.example.points.PointPosition

sealed interface ReverseGeocodeResult {
    data class Found(val address: String) : ReverseGeocodeResult
    data object None : ReverseGeocodeResult
    data object Unavailable : ReverseGeocodeResult
}

/** External I/O boundary. Call before entering any point creation transaction. */
fun interface ReverseGeocoder {
    suspend fun lookup(position: PointPosition): ReverseGeocodeResult
}

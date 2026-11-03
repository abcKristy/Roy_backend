package com.example.points

/** Validates all business fields before the creation transaction starts. */
fun CreatePointCommand.normalized(): CreatePointCommand {
    require(position.latitude.isFinite() && position.latitude in -90.0..90.0) { "Invalid latitude" }
    require(position.longitude.isFinite() && position.longitude in -180.0..180.0) { "Invalid longitude" }

    val normalizedAddress = address.trim()
    require(normalizedAddress.codePointCount(0, normalizedAddress.length) in 1..300) { "Invalid address" }

    return copy(address = normalizedAddress, topic = topic.normalizeTopic().input)
}

package com.example.auth

internal fun normalizeDisplayName(value: String): String? {
    val name = value.trim()
    if (name.codePointCount(0, name.length) !in 1..80 ||
        name.codePoints().anyMatch { codePoint -> codePoint == 0 || codePoint in 0xD800..0xDFFF }
    ) return null
    return name
}

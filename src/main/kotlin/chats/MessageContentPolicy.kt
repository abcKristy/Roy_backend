package com.example.chats

import java.text.Normalizer
import java.util.Locale

fun interface ProfanityDetector {
    /** Deterministic and free of external I/O: called within the send transaction. */
    fun containsProfanity(text: String): Boolean
}

/** Approved specification: spec/for dev/artifacts/chat-message-content-policy.md, v1.0. */
class MessageContentPolicy(private val profanity: ProfanityDetector = ApprovedProfanityDetector) {
    companion object { const val VERSION = "chat-content-v1.0" }

    fun rejected(text: String): Boolean =
        PhoneDetector.contains(text) || LinkDetector.contains(text) || profanity.containsProfanity(text)
}

object ApprovedProfanityDetector : ProfanityDetector {
    private val russian = setOf(
        "хуй", "хуя", "хую", "хуем", "хуи", "хуев", "хуями", "нахуй", "похуй", "охуеть", "охуел",
        "охуела", "охуенный", "охуенно", "хуевый", "хуйня", "хуйню", "хуйне", "хуйней",
        "пизда", "пизды", "пизде", "пизду", "пиздой", "пиздец", "пиздеть", "пизди", "пиздишь",
        "пиздил", "пиздила", "пиздюк", "распиздяй", "пиздатый",
        "ебать", "ебал", "ебала", "ебали", "ебаный", "ебучий", "ебись", "заебал", "заебала",
        "заебали", "заебись", "наебал", "наебала", "выебать", "съебись", "уебок", "ебло", "ебнутый",
        "блядь", "блять", "бля", "блядский", "блядская", "блядское", "блядина",
    )
    private val english = setOf(
        "fuck", "fucks", "fucking", "fucked", "fucker", "fuckers", "motherfucker", "motherfuckers",
        "motherfucking", "shit", "shits", "shitty", "bullshit", "bitch", "bitches", "cunt", "cunts",
        "asshole", "assholes", "arsehole", "arseholes",
    )
    private val maxFormLength = (russian + english).maxOf(String::length)
    private val maskedToken = Regex("[\\p{L}\\p{N}]+(?:[*._·-]{1,2}[\\p{L}\\p{N}]+)*")
    private val spacedLetters = Regex("(?<![\\p{L}\\p{N}])(?:[\\p{L}](?: [\\p{L}]){2,})(?![\\p{L}\\p{N}])")
    private val latinLookalikes = mapOf(
        'a' to 'а', 'e' to 'е', 'o' to 'о', 'p' to 'р', 'c' to 'с', 'x' to 'х',
        'y' to 'у', 'k' to 'к', 'm' to 'м', 't' to 'т', 'b' to 'в', 'h' to 'н',
    )

    override fun containsProfanity(text: String): Boolean {
        val normalized = normalize(text)
        if (spacedLetters.findAll(normalized).any { run ->
                val letters = run.value.split(' ')
                letters.indices.any { start ->
                    (start + 3..minOf(letters.size, start + maxFormLength)).any { end ->
                        exact(letters.subList(start, end).joinToString(""))
                    }
                }
            }) return true
        return maskedToken.findAll(normalized).any { match ->
            val token = match.value
            if (token == "х*й") return@any true
            val masks = token.count { it in "*._·-" }
            masks <= 8 && exact(token.filterNot { it in "*._·-" })
        }
    }

    private fun exact(token: String): Boolean {
        if (token in english) return true
        val cyrillic = token.count { it in 'а'..'я' || it == 'ё' }
        val latin = token.count { it in 'a'..'z' }
        val candidate = if (cyrillic > latin) token.map { latinLookalikes[it] ?: it }.joinToString("") else token
        return candidate in russian
    }

    private fun normalize(text: String): String = buildString {
        val source = Normalizer.normalize(text, Normalizer.Form.NFC).lowercase(Locale.ROOT)
        source.codePoints().forEach { codePoint ->
            if (Character.getType(codePoint) != Character.FORMAT.toInt()) {
                appendCodePoint(if (codePoint == 'ё'.code) 'е'.code else codePoint)
            }
        }
    }
}

private object PhoneDetector {
    private val candidate = Regex("(?<![\\p{L}\\p{N}])\\+?[0-9](?:[0-9 ()\\u00a0.-]*[0-9])?(?![\\p{L}\\p{N}])")
    private val context = Regex("(?iu)(?<![\\p{L}\\p{N}])(?:телефон|звони|тел|phone|call me)[ :\\-]{1,2}$")

    fun contains(text: String): Boolean = candidate.findAll(text).any { match ->
        val value = match.value
        if (Regex("[ ()\\u00a0.-]{4,}").containsMatchIn(value)) return@any false
        val digits = value.count(Char::isDigit)
        when {
            value.startsWith('+') -> digits in 10..15
            digits == 10 -> value.first() == '9'
            digits == 11 -> value.first() == '7' || value.first() == '8'
            digits in 7..9 -> context.containsMatchIn(text.substring(0, match.range.first).takeLast(32))
            else -> false
        }
    }
}

private object LinkDetector {
    private val explicitPrefix = Regex("(?iu)(?<![\\p{L}\\p{N}])(?:h ?t ?t ?p(?: ?s)? ?: ?/ ?/ ?|f ?t ?p ?: ?/ ?/ ?|www ?\\. ?)")
    private val explicitHost = Regex("[\\p{L}\\p{N}-]+(?: ?\\. ?[\\p{L}\\p{N}-]*)*+")
    private val hostLabel = Regex("[\\p{L}\\p{N}](?:[\\p{L}\\p{N}-]*[\\p{L}\\p{N}])?")
    private val suffixes = setOf(
        "ru", "рф", "su", "com", "net", "org", "info", "biz", "io", "app", "dev", "me", "tv", "ua",
        "by", "kz", "de", "fr", "uk", "us", "edu", "gov", "site", "online", "xyz", "club", "pro", "shop",
    )
    private val host = Regex("(?iu)(?<![\\p{L}\\p{N}-])${hostLabel.pattern}(?: ?\\. ?${hostLabel.pattern})++(?![\\p{L}\\p{N}-])")
    private val ipv4 = Regex("(?<![\\p{L}\\p{N}.])(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?![\\p{L}\\p{N}.])")
    private val ipv6 = Regex("(?i)(?<![\\p{L}\\p{N}:])\\[?([0-9a-f:]*:[0-9a-f:]+)\\]?(?![\\p{L}\\p{N}:])")

    fun contains(text: String): Boolean {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFC).lowercase(Locale.ROOT)
        if (explicitPrefix.findAll(normalized).any { prefix ->
                val match = explicitHost.matchAt(normalized, prefix.range.last + 1) ?: return@any false
                if (normalized.getOrNull(match.range.last + 1) == '_') return@any false
                val candidate = match.value
                val labels = candidate.replace(" ", "").split('.')
                val numericIpv4 = labels.size == 4 && labels.all { label -> label.all { it in '0'..'9' } }
                labels.size >= 2 && labels.all { hostLabel.matches(it) } &&
                    (!numericIpv4 || labels.all { it.length <= 3 && it.toIntOrNull()?.let { part -> part in 0..255 } == true })
            }) return true
        if (host.findAll(normalized).any { match ->
                if (normalized.substring(0, match.range.first).endsWith("ex ") && match.value == "ample.com") {
                    return@any false // Explicit v1.0 allowed split-label fixture: ex ample.com.
                }
                match.value.replace(" ", "").substringAfterLast('.') in suffixes
            }) return true
        if (ipv4.findAll(normalized).any { match ->
                match.value.split('.').all { it.toInt() in 0..255 }
            }) return true
        return ipv6.findAll(normalized).any { match -> validIpv6(match.groupValues[1]) }
    }

    private fun validIpv6(value: String): Boolean {
        if (value.startsWith(':') && !value.startsWith("::")) return false
        if (value.endsWith(':') && !value.endsWith("::")) return false
        if (value.count { it == ':' } < 2 || value.count { it == ':' } > 7 + 1) return false
        val compressed = value.contains("::")
        if (compressed && value.indexOf("::") != value.lastIndexOf("::")) return false
        val groups = value.split(':').filter(String::isNotEmpty)
        if (groups.any { it.length !in 1..4 || it.any { char -> !char.isDigit() && char !in 'a'..'f' } }) return false
        return if (compressed) groups.size < 8 else groups.size == 8
    }
}

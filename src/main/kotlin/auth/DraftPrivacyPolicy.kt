package com.example.auth

import java.net.URI
import java.security.MessageDigest
import java.time.Instant

/** Local development publication only. Never treat this version as production consent. */
class DraftPrivacyPolicy(
    publicBaseUrl: URI,
    val document: String = requireNotNull(DraftPrivacyPolicy::class.java.getResourceAsStream("/legal/privacy-policy-draft.md")) {
        "Draft privacy policy resource is missing"
    }.bufferedReader(Charsets.UTF_8).use { it.readText() },
) : PublishedPrivacyPolicy {
    override val version: String = VERSION
    val publishedAt: Instant = PUBLISHED_AT
    val url: String = publicBaseUrl.toString().trimEnd('/') + DOCUMENT_PATH
    init {
        val normalized = document.replace("\r\n", "\n")
        val headerVersion = HEADER.matchEntire(normalized.lineSequence().drop(2).firstOrNull().orEmpty())
            ?.groupValues?.get(1)
        require(headerVersion == VERSION) { "Draft privacy policy header version does not match metadata" }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(normalized.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        require(digest == DOCUMENT_SHA256) {
            "Draft privacy policy content changed: update version, publishedAt and digest together"
        }
    }

    companion object {
        const val VERSION = "draft-2026-11-01.1"
        const val DOCUMENT_PATH = "/api/v1/legal/privacy/document"
        val PUBLISHED_AT: Instant = Instant.parse("2026-11-01T15:36:07Z")
        private const val DOCUMENT_SHA256 = "0051537547e8c2ff60b45e406aace17f9a017f85bbc6355682e9bb3183df0529"
        private val HEADER = Regex("""^\*\*Версия: (draft-\d{4}-\d{2}-\d{2}\.\d+)\. Статус: [^*]+\*\*(?: .*)?$""")
    }
}

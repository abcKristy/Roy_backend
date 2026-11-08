package com.example.auth

import java.net.URI
import java.security.MessageDigest
import java.time.Instant

/** Local development publication only. Never treat this version as production consent. */
class DraftPrivacyPolicy(
    publicBaseUrl: URI,
    override val document: String = requireNotNull(DraftPrivacyPolicy::class.java.getResourceAsStream("/legal/privacy-policy-draft.md")) {
        "Draft privacy policy resource is missing"
    }.bufferedReader(Charsets.UTF_8).use { it.readText() },
) : PrivacyPolicyDocument {
    override val version: String = VERSION
    override val publishedAt: Instant = PUBLISHED_AT
    override val url: String = publicBaseUrl.resolve(PrivacyPolicyDocument.PATH).toString()
    init {
        val normalized = document.replace("\r\n", "\n")
        require(headerVersion(normalized) == VERSION) { "Draft privacy policy header version does not match metadata" }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(normalized.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        require(digest == DOCUMENT_SHA256) {
            "Draft privacy policy content changed: update version, publishedAt and digest together"
        }
    }

    companion object {
        const val VERSION = "draft-2026-11-01.2"
        val PUBLISHED_AT: Instant = Instant.parse("2026-11-01T19:02:11Z")
        private const val DOCUMENT_SHA256 = "c7882235db9aeebc149e0ae784d90cd3f8ea9966894bdd8f6274436e2f327843"
        private val HEADER = Regex("""^\*\*Версия: (draft-\d{4}-\d{2}-\d{2}\.\d+)\. Статус: [^*]+\*\*(?: .*)?$""")

        internal fun headerVersion(document: String): String? =
            HEADER.matchEntire(document.lineSequence().drop(2).firstOrNull().orEmpty())?.groupValues?.get(1)
    }
}

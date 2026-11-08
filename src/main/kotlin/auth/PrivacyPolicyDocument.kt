package com.example.auth

import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/** One version of a published policy and the document served with it. */
sealed interface PrivacyPolicyDocument : PublishedPrivacyPolicy {
    override val version: String
    val publishedAt: Instant
    val url: String
    val document: String

    companion object {
        const val PATH = "/api/v1/legal/privacy/document"
    }
}

internal class ProductionPrivacyPolicy(
    publicBaseUrl: URI,
    file: Path,
    override val version: String,
    override val publishedAt: Instant,
) : PrivacyPolicyDocument {
    override val url: String = publicBaseUrl.resolve(PrivacyPolicyDocument.PATH).toString()
    override val document: String

    init {
        require(!publishedAt.isAfter(Instant.now())) { "AUTH_PRIVACY_POLICY_PUBLISHED_AT must not be in the future" }
        val readError = "AUTH_PRIVACY_POLICY_FILE must be a readable UTF-8 file"
        document = try {
            require(Files.isRegularFile(file)) { readError }
            Files.readString(file)
        } catch (_: IOException) {
            throw IllegalArgumentException(readError)
        } catch (_: SecurityException) {
            throw IllegalArgumentException(readError)
        }
        require(document.isNotBlank()) { "AUTH_PRIVACY_POLICY_FILE must not be empty" }
        require(DraftPrivacyPolicy.headerVersion(document) == null) { "AUTH_PRIVACY_POLICY_FILE contains a local draft" }
    }
}

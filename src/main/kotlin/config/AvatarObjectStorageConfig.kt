package com.example.config

/** The key ID selects the KMS key. Static S3 credentials belong to the avatar service account. */
class AvatarObjectStorageConfig(
    val bucket: String,
    val kmsKeyId: String,
    val accessKeyId: String,
    val secretAccessKey: String,
) {
    init {
        require(bucket.length in 3..63 && BUCKET_NAME.matches(bucket) && !IP_ADDRESS.matches(bucket)) {
            "AVATAR_STORAGE_BUCKET must be an Object Storage bucket name"
        }
        require(kmsKeyId.isVisibleAscii()) { "AVATAR_STORAGE_KMS_KEY_ID is invalid" }
        require(accessKeyId.isVisibleAscii()) { "AVATAR_STORAGE_ACCESS_KEY_ID is invalid" }
        require(secretAccessKey.isVisibleAscii()) { "AVATAR_STORAGE_SECRET_ACCESS_KEY is invalid" }
    }

    private fun String.isVisibleAscii(): Boolean = isNotEmpty() && all { it in '!'..'~' }

    private companion object {
        val BUCKET_NAME = Regex("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)*")
        val IP_ADDRESS = Regex("[0-9]+(?:\\.[0-9]+){3}")
    }
}

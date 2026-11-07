package com.example.storage

import java.io.IOException
import java.util.UUID

enum class AvatarContentType(val mimeType: String) {
    JPEG("image/jpeg"),
    PNG("image/png"),
}

/** The image processor supplies the re-encoded bytes and the actual format. */
class AvatarImage(val bytes: ByteArray, val contentType: AvatarContentType) {
    init {
        require(bytes.size in 1..MAX_STORED_AVATAR_BYTES) { "Invalid stored avatar size" }
    }
}

internal const val MAX_STORED_AVATAR_BYTES = 5 * 1024 * 1024

class AvatarStorageException(cause: Throwable? = null) : IOException("Avatar storage unavailable", cause)

/** Private object I/O. Call outside database transactions. Keys are for server use only. */
interface AvatarStore {
    /** Creates a new object under a server-allocated UUID v4. An existing object is never overwritten. */
    suspend fun save(image: AvatarImage, objectId: UUID = UUID.randomUUID()): String

    /** Returns null for a missing object. Storage failures throw [AvatarStorageException]. */
    suspend fun read(objectKey: String): AvatarImage?

    /** A missing object is already deleted. Storage failures throw [AvatarStorageException]. */
    suspend fun delete(objectKey: String)
}

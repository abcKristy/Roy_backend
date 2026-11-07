package com.example.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.AclEntryFlag.FILE_INHERIT
import java.nio.file.attribute.AclEntryType.ALLOW
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.GroupPrincipal
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID

/** Local-only store. Use a service-owned directory outside any static file root. */
class LocalAvatarStore(directory: Path) : AvatarStore {
    private val root = directory.toAbsolutePath().normalize()
    private val ownerOnly = PosixFilePermissions.fromString("rwx------")
    private val posix = "posix" in root.fileSystem.supportedFileAttributeViews()
    private val serviceOwner by lazy {
        root.fileSystem.userPrincipalLookupService.lookupPrincipalByName(System.getProperty("user.name"))
    }
    private val initializedRoot by lazy {
        if (posix) {
            var ancestor = root
            while (Files.notExists(ancestor, NOFOLLOW_LINKS)) {
                ancestor = ancestor.parent ?: throw AvatarStorageException()
            }
            checkCanonicalDirectory(ancestor)
            Files.createDirectories(root, PosixFilePermissions.asFileAttribute(ownerOnly))
        }
        root
    }

    override suspend fun save(image: AvatarImage): String = storageIo {
        val directory = checkedDirectory()
        val staged = Files.createTempFile(directory, ".avatar-", ".tmp")
        try {
            checkPrivate(staged, directory = false)
            Files.newOutputStream(staged).use { output ->
                output.write(when (image.contentType) {
                    AvatarContentType.JPEG -> JPEG_MARKER
                    AvatarContentType.PNG -> PNG_MARKER
                })
                output.write(image.bytes)
            }
            val key = UUID.randomUUID().toString()
            // Link the complete file atomically. A collision cannot replace an existing object.
            val published = Files.createLink(directory.resolve(key), staged)
            try {
                Files.deleteIfExists(staged)
            } catch (failure: IOException) {
                try {
                    Files.deleteIfExists(published)
                } catch (rollbackFailure: IOException) {
                    failure.addSuppressed(rollbackFailure)
                }
                throw failure
            }
            key
        } catch (failure: Exception) {
            try {
                Files.deleteIfExists(staged)
            } catch (cleanupFailure: IOException) {
                failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }
    }

    override suspend fun read(objectKey: String): AvatarImage? = storageIo {
        val path = objectPath(objectKey)
        try {
            if (regularObjectAttributes(path).size() !in 2L..(MAX_STORED_AVATAR_BYTES + 1L)) {
                throw AvatarStorageException()
            }
            Files.newInputStream(path, NOFOLLOW_LINKS).use { input ->
                val contentType = when (input.read()) {
                    JPEG_MARKER -> AvatarContentType.JPEG
                    PNG_MARKER -> AvatarContentType.PNG
                    else -> throw AvatarStorageException()
                }
                val bytes = input.readNBytes(MAX_STORED_AVATAR_BYTES + 1)
                if (bytes.size !in 1..MAX_STORED_AVATAR_BYTES) throw AvatarStorageException()
                AvatarImage(bytes, contentType)
            }
        } catch (_: NoSuchFileException) {
            checkedDirectory()
            null
        }
    }

    override suspend fun delete(objectKey: String): Unit = storageIo {
        val path = objectPath(objectKey)
        try {
            regularObjectAttributes(path)
            Files.delete(path)
        } catch (_: NoSuchFileException) {
            checkedDirectory()
        }
    }

    private fun regularObjectAttributes(path: Path): BasicFileAttributes =
        Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS).also {
            if (!it.isRegularFile) throw AvatarStorageException()
            checkPrivate(path, directory = false)
        }

    private fun objectPath(key: String): Path {
        require(OBJECT_KEY.matches(key)) { "Invalid avatar object key" }
        return checkedDirectory().resolve(key)
    }

    private fun checkedDirectory(): Path {
        val directory = initializedRoot
        checkCanonicalDirectory(directory)
        checkPrivate(directory, directory = true)
        return directory
    }

    private fun checkCanonicalDirectory(path: Path) {
        if (!Files.isDirectory(path, NOFOLLOW_LINKS) || path.toRealPath() != path) {
            throw AvatarStorageException()
        }
    }

    private fun checkPrivate(path: Path, directory: Boolean) {
        if (posix) {
            if (Files.getOwner(path, NOFOLLOW_LINKS) != serviceOwner ||
                !ownerOnly.containsAll(Files.getPosixFilePermissions(path, NOFOLLOW_LINKS))
            ) {
                throw AvatarStorageException()
            }
            return
        }
        val view = Files.getFileAttributeView(path, AclFileAttributeView::class.java, NOFOLLOW_LINKS)
            ?: throw AvatarStorageException()
        val owner = view.owner
        val grants = view.acl.filter { it.type() == ALLOW }
        if (owner is GroupPrincipal || owner != serviceOwner ||
            grants.isEmpty() || grants.any { it.principal() != owner } ||
            (directory && grants.none { FILE_INHERIT in it.flags() })
        ) {
            throw AvatarStorageException()
        }
    }

    private suspend fun <T> storageIo(block: () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (failure: AvatarStorageException) {
            throw failure
        } catch (failure: IOException) {
            throw AvatarStorageException(failure)
        } catch (failure: UnsupportedOperationException) {
            throw AvatarStorageException(failure)
        } catch (failure: SecurityException) {
            throw AvatarStorageException(failure)
        }
    }

    private companion object {
        const val JPEG_MARKER = 1
        const val PNG_MARKER = 2
        val OBJECT_KEY = Regex("[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}")
    }
}

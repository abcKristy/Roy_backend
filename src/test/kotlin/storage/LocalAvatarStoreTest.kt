package com.example.storage

import com.example.api.ApiException
import com.example.auth.Argon2idPasswordHasher
import com.example.auth.PasswordHashingWork
import com.example.auth.ProfileService
import com.example.persistence.DatabaseRuntime
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryFlag.DIRECTORY_INHERIT
import java.nio.file.attribute.AclEntryFlag.FILE_INHERIT
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType.ALLOW
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalAvatarStoreTest {
    @Test
    fun `different saves use opaque keys and preserve bytes and type across store instances`() = runBlocking {
        inDirectory { directory ->
            val root = Files.createDirectory(directory.resolve("avatars"))
            restrictToOwner(root)
            val store = LocalAvatarStore(root)
            val png = AvatarImage(byteArrayOf(1, 2, 3), AvatarContentType.PNG)
            val jpeg = AvatarImage(byteArrayOf(1, 2, 3), AvatarContentType.JPEG)
            val pngKey = store.save(png)
            val jpegKey = store.save(jpeg)
            val duplicateKey = store.save(png)

            assertNotEquals(pngKey, jpegKey)
            assertNotEquals(pngKey, duplicateKey)
            assertNotEquals(jpegKey, duplicateKey)
            for (key in listOf(pngKey, jpegKey, duplicateKey)) {
                assertEquals(key, UUID.fromString(key).toString())
                assertEquals(4, UUID.fromString(key).version())
            }
            val reopened = LocalAvatarStore(directory.resolve("avatars"))
            assertImage(png, reopened.read(pngKey))
            assertImage(jpeg, reopened.read(jpegKey))
            assertImage(png, reopened.read(duplicateKey))
        }
    }

    @Test
    fun `repeated deletion leaves unrelated objects intact`() = runBlocking {
        inDirectory { directory ->
            val store = LocalAvatarStore(directory)
            val image = AvatarImage(byteArrayOf(1, 2, 3), AvatarContentType.PNG)
            val oldKey = store.save(image)
            val newerKey = store.save(image)

            store.delete(oldKey)
            store.delete(oldKey)

            assertNull(store.read(oldKey))
            assertImage(image, store.read(newerKey))
            Files.list(directory).use { entries ->
                assertEquals(listOf(newerKey), entries.map { it.fileName.toString() }.toList())
            }
        }
    }

    @Test
    fun `a permissive root fails before any avatar bytes are written`() = runBlocking {
        inDirectory { directory ->
            val image = AvatarImage(byteArrayOf(1, 2, 3), AvatarContentType.PNG)
            if (Files.getFileAttributeView(directory, AclFileAttributeView::class.java) != null) {
                val unprovisioned = directory.resolve("unprovisioned")
                assertFailsWith<AvatarStorageException> { LocalAvatarStore(unprovisioned).save(image) }
                assertTrue(Files.notExists(unprovisioned))
            }
            allowAnotherPrincipal(directory, directory = true)
            val store = LocalAvatarStore(directory)
            assertFailsWith<AvatarStorageException> { store.save(image) }
            assertFailsWith<AvatarStorageException> { store.read(UUID.randomUUID().toString()) }
            assertFailsWith<AvatarStorageException> { store.delete(UUID.randomUUID().toString()) }
            Files.list(directory).use { assertEquals(0L, it.count()) }
        }
    }

    @Test
    fun `a permissive object in a private root cannot be served or deleted as a valid object`() = runBlocking {
        inDirectory { directory ->
            val store = LocalAvatarStore(directory)
            val key = store.save(AvatarImage(byteArrayOf(1, 2, 3), AvatarContentType.PNG))
            val objectPath = directory.resolve(key)

            allowAnotherPrincipal(objectPath, directory = false)

            assertFailsWith<AvatarStorageException> { store.read(key) }
            assertFailsWith<AvatarStorageException> { store.delete(key) }
            assertTrue(Files.isRegularFile(objectPath))
        }
    }

    @Test
    fun `invalid keys cannot access or delete an unrelated file`() = runBlocking {
        inDirectory { directory ->
            val store = LocalAvatarStore(directory.resolve("avatars"))
            val outside = Files.writeString(directory.resolve("outside"), "preserved")
            for (key in listOf("../outside", "..\\outside", outside.toString(), "1-1-1-1-1")) {
                assertFailsWith<IllegalArgumentException> { store.read(key) }
                assertFailsWith<IllegalArgumentException> { store.delete(key) }
            }
            assertEquals("preserved", Files.readString(outside))
        }
    }

    @Test
    fun `bad directory and corrupt records surface storage failures`() = runBlocking {
        inDirectory { directory ->
            val image = AvatarImage(byteArrayOf(1, 2, 3), AvatarContentType.PNG)
            val badRoot = Files.writeString(directory.resolve("bad-root"), "preserved")
            val misconfigured = LocalAvatarStore(badRoot)
            assertFailsWith<AvatarStorageException> { misconfigured.save(image) }
            val missingKey = UUID.randomUUID().toString()
            assertFailsWith<AvatarStorageException> { misconfigured.read(missingKey) }
            assertFailsWith<AvatarStorageException> { misconfigured.delete(missingKey) }
            assertEquals("preserved", Files.readString(badRoot))

            val store = LocalAvatarStore(directory)
            Files.write(directory.resolve(missingKey), byteArrayOf(0, 1, 2, 3))
            assertFailsWith<AvatarStorageException> { store.read(missingKey) }
            val oversizedKey = UUID.randomUUID().toString()
            Files.write(directory.resolve(oversizedKey), ByteArray(5 * 1024 * 1024 + 2))
            assertFailsWith<AvatarStorageException> { store.read(oversizedKey) }
            val directoryKey = UUID.randomUUID().toString()
            val unrelatedDirectory = Files.createDirectory(directory.resolve(directoryKey))
            assertFailsWith<AvatarStorageException> { store.delete(directoryKey) }
            assertTrue(Files.isDirectory(unrelatedDirectory))
            assertFailsWith<IllegalArgumentException> { AvatarImage(byteArrayOf(), AvatarContentType.PNG) }
            assertFailsWith<IllegalArgumentException> { AvatarImage(ByteArray(5 * 1024 * 1024 + 1), AvatarContentType.PNG) }
        }
    }

    @Test
    fun `profile stages through injected storage and reports unavailable without touching the database`() = runBlocking {
        inDirectory { directory ->
            val image = AvatarImage(byteArrayOf(1, 2, 3), AvatarContentType.PNG)
            val database = object : DatabaseRuntime {
                override suspend fun isReady(): Boolean = error("No database call expected")
                override fun close() = Unit
            }
            val hashing = PasswordHashingWork(Argon2idPasswordHasher())
            val store = LocalAvatarStore(directory)
            val profile = ProfileService(database, hashing, store)
            assertImage(image, store.read(profile.stageAvatar(image)))

            val badRoot = Files.writeString(directory.resolve("bad-root"), "preserved")
            for (unavailable in listOf(null, LocalAvatarStore(badRoot))) {
                val failure = assertFailsWith<ApiException> {
                    ProfileService(database, hashing, unavailable).stageAvatar(image)
                }
                assertEquals(HttpStatusCode.ServiceUnavailable, failure.status)
                assertEquals("AVATAR_STORAGE_UNAVAILABLE", failure.code)
            }
        }
    }

    private suspend fun inDirectory(block: suspend (Path) -> Unit) {
        val directory = Files.createTempDirectory("avatar-store-test-").toRealPath()
        try {
            restrictToOwner(directory)
            block(directory)
        } finally {
            check(directory.toFile().deleteRecursively())
        }
    }

    private fun restrictToOwner(directory: Path) {
        val view = Files.getFileAttributeView(directory, AclFileAttributeView::class.java)
        if (view != null) {
            view.acl = listOf(
                AclEntry.newBuilder()
                    .setType(ALLOW)
                    .setPrincipal(view.owner)
                    .setPermissions(*AclEntryPermission.entries.toTypedArray())
                    .setFlags(FILE_INHERIT, DIRECTORY_INHERIT)
                    .build(),
            )
        } else {
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"))
        }
    }

    private fun allowAnotherPrincipal(path: Path, directory: Boolean) {
        val view = Files.getFileAttributeView(path, AclFileAttributeView::class.java)
        if (view != null) {
            val parent = if (directory) path.parent else path.parent.parent
            val parentAcl = Files.getFileAttributeView(parent, AclFileAttributeView::class.java).acl
            val other = parentAcl.first { it.type() == ALLOW && it.principal() != view.owner }.principal()
            val grant = AclEntry.newBuilder()
                .setType(ALLOW)
                .setPrincipal(other)
                .setPermissions(AclEntryPermission.READ_DATA)
            if (directory) grant.setFlags(FILE_INHERIT)
            view.acl = view.acl + grant.build()
            assertTrue(view.acl.any { it.type() == ALLOW && it.principal() == other })
        } else {
            val permissions = if (directory) "rwxr-xr-x" else "rw-r--r--"
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions))
        }
    }

    private fun assertImage(expected: AvatarImage, actual: AvatarImage?) {
        assertNotNull(actual)
        assertEquals(expected.contentType, actual.contentType)
        assertContentEquals(expected.bytes, actual.bytes)
    }
}

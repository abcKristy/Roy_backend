package com.example.storage

import com.example.config.AvatarObjectStorageConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.http.apache5.Apache5HttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.s3.model.ServerSideEncryption
import java.io.IOException
import java.net.URI
import java.time.Duration
import java.util.UUID

/** Server-only adapter for a dedicated private Yandex Object Storage bucket. */
internal class YandexAvatarStore(
    private val config: AvatarObjectStorageConfig,
    private val client: S3Client = createClient(config, ENDPOINT),
) : AvatarStore, AutoCloseable {

    override suspend fun save(image: AvatarImage, objectId: UUID): String = storageIo {
        val key = objectId.toString()
        require(OBJECT_KEY.matches(key)) { "Invalid avatar object key" }
        val request = PutObjectRequest.builder()
            .bucket(config.bucket)
            .key(key)
            .contentType(image.contentType.mimeType)
            .ifNoneMatch("*")
            .serverSideEncryption(ServerSideEncryption.AWS_KMS)
            .ssekmsKeyId(config.kmsKeyId)
            .build()
        client.putObject(request, RequestBody.fromBytes(image.bytes))
        key
    }

    override suspend fun read(objectKey: String): AvatarImage? = storageIo {
        require(OBJECT_KEY.matches(objectKey)) { "Invalid avatar object key" }
        val request = GetObjectRequest.builder().bucket(config.bucket).key(objectKey).build()
        try {
            client.getObject(request).use { input ->
                val length = input.response().contentLength()
                val contentType = AvatarContentType.entries.firstOrNull {
                    it.mimeType == input.response().contentType()
                }
                if (length == null || length !in 1L..MAX_STORED_AVATAR_BYTES.toLong() || contentType == null) {
                    input.abort()
                    throw AvatarStorageException()
                }
                val bytes = input.readNBytes(length.toInt() + 1)
                if (bytes.size.toLong() != length) {
                    input.abort()
                    throw AvatarStorageException()
                }
                AvatarImage(bytes, contentType)
            }
        } catch (failure: S3Exception) {
            if (!failure.isMissingKey()) throw failure
            null
        }
    }

    override suspend fun delete(objectKey: String): Unit = storageIo {
        require(OBJECT_KEY.matches(objectKey)) { "Invalid avatar object key" }
        val request = DeleteObjectRequest.builder().bucket(config.bucket).key(objectKey).build()
        try {
            client.deleteObject(request)
        } catch (failure: S3Exception) {
            if (!failure.isMissingKey()) throw failure
        }
    }

    override fun close() = client.close()

    private fun S3Exception.isMissingKey(): Boolean =
        statusCode() == 404 && awsErrorDetails()?.errorCode() == "NoSuchKey"

    private suspend fun <T> storageIo(block: () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (failure: AvatarStorageException) {
            throw failure
        } catch (_: SdkException) {
            // SDK exceptions may embed the provider payload, object path and request metadata.
            throw AvatarStorageException()
        } catch (_: IOException) {
            throw AvatarStorageException()
        }
    }

    companion object {
        private val ENDPOINT: URI = URI.create("https://storage.yandexcloud.net")
        private val OBJECT_KEY = Regex("[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}")

        // The composition root uses the fixed endpoint. Tests inject a client with a loopback endpoint.
        internal fun createClient(config: AvatarObjectStorageConfig, endpoint: URI): S3Client =
            S3Client.builder()
                .endpointOverride(endpoint)
                .region(Region.of("ru-central1"))
                .serviceConfiguration(S3Configuration.builder()
                    .pathStyleAccessEnabled(true)
                    .chunkedEncodingEnabled(false)
                    .build())
                .credentialsProvider(
                    StaticCredentialsProvider.create(AwsBasicCredentials.create(config.accessKeyId, config.secretAccessKey)),
                )
                .httpClientBuilder(Apache5HttpClient.builder()
                    .connectionTimeout(Duration.ofSeconds(3))
                    .connectionAcquisitionTimeout(Duration.ofSeconds(3))
                    .socketTimeout(Duration.ofSeconds(5)))
                .overrideConfiguration { overrides ->
                    overrides.apiCallAttemptTimeout(Duration.ofSeconds(7)).apiCallTimeout(Duration.ofSeconds(12))
                }
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .build()
    }
}

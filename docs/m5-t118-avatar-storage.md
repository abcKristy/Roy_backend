# T118 private avatar storage

Backend baseline `c93ccd6b6253b3d4eba3cd01f3d959bc2e280f9c` on `feat/avatar-storage-access`. The checkout contains Flyway V001–V016. The T118 fixture uses an SDK client against a loopback HTTP server on Windows and JDK 17.0.10. It does not open a database connection or apply migrations.

The JVM backend pins AWS SDK for Java v2 `2.57.4` and its Apache 5 HTTP client. The production client uses path-style requests against `https://storage.yandexcloud.net` in `ru-central1`. Local mode selects the established `.local/avatars` file adapter. In production all four storage settings are required together to enable the adapter. Leaving them all empty leaves avatar staging unavailable. [AWS SDK release](https://github.com/aws/aws-sdk-java-v2/releases/tag/2.57.4), [Maven Central artifact](https://repo.maven.apache.org/maven2/software/amazon/awssdk/s3/2.57.4/s3-2.57.4.pom), [Yandex SDK configuration](https://yandex.cloud/en/docs/storage/tools/aws-sdk-java).

- `AVATAR_STORAGE_BUCKET` is the dedicated private avatar bucket name.
- `AVATAR_STORAGE_KMS_KEY_ID` selects its symmetric encryption key.
- `AVATAR_STORAGE_ACCESS_KEY_ID` and `AVATAR_STORAGE_SECRET_ACCESS_KEY` are the scoped service account's static S3 credentials. Supply values through the deployment secret mechanism and keep them outside Git.

The bucket must restrict anonymous reads, listing and settings access and have no public ACL or policy. Configure default KMS encryption with the same key. The runtime account needs permission for the avatar object operations and encryption/decryption on that key. Keep the backup bucket separate from the runtime account. A versioned bucket can retain old object versions after `DeleteObject`, so the active avatar bucket requires unversioned deletion for this adapter. The deployment configuration and its permissions require independent evidence. [Yandex bucket access](https://yandex.cloud/en/docs/storage/concepts/bucket#public-access-to-buckets), [Yandex encryption](https://yandex.cloud/en/docs/storage/concepts/encryption), [Yandex versioning](https://yandex.cloud/en/docs/storage/concepts/versioning).

The port stores the processed image under a new UUID v4. The signed PUT includes `If-None-Match: *` and the SSE-KMS headers. It sends a fixed-length payload, and a retry of that PUT cannot overwrite the same key. Reads accept declared JPEG/PNG objects of 1 byte through 5 MiB and bound the body read. An explicit provider `NoSuchKey` maps to absence. Delete accepts a missing key and other provider failures become a sanitized `AvatarStorageException`. The established staging seam maps this exception to `503 AVATAR_STORAGE_UNAVAILABLE` outside the database transaction. SDK and HTTP client diagnostic logging are disabled. The Ktor application closes the cloud client after its jobs stop and closes it if startup fails. [Yandex PutObject contract](https://yandex.cloud/en/docs/storage/s3/api-ref/object/upload).

A PUT can complete remotely after its response is lost. The failed staging call returns no key and a later request may stage a different object. Database swap and cleanup of unreferenced objects remain in T119. T120 owns the authorized avatar read route.

## Local evidence

The focused Gradle run on 2026-11-07 passed 24 tests with zero failures, errors or skips. It included four new SDK loopback scenarios, seven configuration tests, seven local store tests and six upload tests.

```powershell
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-17'
.\gradlew.bat --no-daemon --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=512m' '-Pkotlin.compiler.execution.strategy=in-process' test --tests com.example.storage.YandexAvatarStoreTest --tests com.example.config.ServerConfigTest --tests com.example.storage.LocalAvatarStoreTest --tests com.example.api.AvatarUploadTest
```

- PUT sent the original bytes with the configured MIME type and KMS headers. The fixture saw a SigV4 authorization header scoped to `ru-central1/s3`, with the conditional header in the signed header list. Two saves produced distinct UUID v4 keys. The requests carried no ACL grants.
- A stored object round-tripped through GET. Two DELETE calls removed it and preserved the other object. An explicit `NoSuchKey` response returned absence.
- Rejected access, a missing bucket and a failed conditional write became safe storage failures. Missing length, oversized objects, and unexpected content type also failed safely. Invalid object keys did not reach the fixture.
- Partial production storage settings and cloud configuration in local mode were rejected. The existing local storage and upload behavior passed the same focused run.

The tests compiled unsuccessfully before the adapter and configuration existed. The first post-implementation run exposed the SDK's default signed chunk framing in the upload body. The passing run configured fixed-length uploads and exercised the Apache 5 client. The error assertion permits coroutine stacktrace recovery to copy the sanitized exception while checking the entire cause chain for provider exceptions.

## External acceptance on stages 4–5

A private Yandex bucket, its KMS key and scoped service account access are provisioned at stage 4. Authenticated write, byte-for-byte read, delete and repeated delete, anonymous read/list denial, and actual KMS encryption have no live provider evidence. The loopback server exercises request/response handling locally and does not satisfy this criterion.

Planning files under `spec/plans/` and `project/postman/Рой.postman_collection.json` live outside backend Git. Swagger fragments under `docs/openapi/` and generated `docs/openapi.json` are versioned in backend Git. T118 adds no HTTP operation.

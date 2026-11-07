# T117 avatar upload component acceptance

Backend baseline `00bd6d4` on `feat/profile-avatar-foundation`. The checkout contains Flyway V001–V015. The fixture ran on Windows with JDK 17.0.10 on 2026-11-07.

`receiveAvatarImage()` accepts one multipart file part named `file` with a declared JPEG or PNG media type. The filename is optional. Header parameters require complete token or quoted-value syntax and case-insensitively unique names. The receiver measures the actual file span independently of declared lengths. The raw limit is inclusive at 5,242,880 bytes. The request is buffered up to that limit plus 8 KiB for part headers and 160 bytes for delimiters, with a one-byte overflow probe. Multipart preambles, epilogues, extra parts and incomplete closing delimiters are rejected. An oversized raw file or envelope returns `413 INVALID_FIELD`. Invalid multipart or image content returns `422 INVALID_FIELD`.

The component verifies the declared type against ImageIO's detected decoder, checks input limits of 8,192 per side and 16,000,000 pixels, and allows two concurrent image processors. PNG validation checks the chunk framing, CRCs, terminal IEND and complete zlib stream against the expected scanline size, including Adam7. The PNG reader receives only pixel-related chunks. JPEG requires a final EOI marker and a warning-free decode. The raster is encoded afresh in its original format, with an aspect-ratio-preserving longest edge of up to 1,024 pixels and PNG transparency. Output contains the encoder's own structural headers and discards source EXIF, text, profiles and other metadata. JPEG EXIF orientation is discarded with the rest of the metadata. The encoded bytes also obey the 5 MiB storage limit.

The Ktor test application invoked the receiver followed by the T116 `ProfileService.stageAvatar` seam with the real owner-only `LocalAvatarStore`. It streamed multipart request bodies without a request `Content-Length`, including file spans of 5,242,880 and 5,242,881 bytes. The exact-limit file was accepted and stored after processing. The extra byte produced `413` even with a forged part `Content-Length`. The stored object stayed byte-for-byte intact and the directory contained only its original key.

- JPEG with an EXIF description and PNG with a private text chunk were accepted. Stored bytes decoded as the corresponding image format, lacked the source metadata and retained the tested PNG alpha pixel.
- A PNG part without a filename and with a valid escaped quoted MIME parameter was accepted and stored. Unterminated MIME and filename values, invalid unquoted syntax and duplicate disposition parameters returned `422` and preserved the original object. Before the review fixes, the filename-less test returned `422` and the unterminated MIME case returned `200`.
- MIME spoofing, unsupported media type and bytes, malformed PNG and JPEG, a truncated PNG zlib stream with a valid chunk CRC, excessive raster dimensions, missing and duplicate file parts, excessive headers and invalid multipart termination returned `422`. Each rejected request preserved the existing object's bytes and left the directory with the same single object.
- The 1,536 × 768 PNG was stored as 1,024 × 512 with alpha and within the storage limit.

Focused verification after review passed six upload tests and seven local storage tests with zero failures or skips.

```powershell
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-17'
.\gradlew.bat --offline --no-daemon --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=512m' '-Pkotlin.compiler.execution.strategy=in-process' test --tests com.example.api.AvatarUploadTest --tests com.example.storage.LocalAvatarStoreTest
```

Before the review fixes, the unfiltered `test` run without a database reported 276 tests with 183 skipped and one failure. `MeetingAcceptanceFixtureIntegrationTest.failure after committed fixture seed removes only its UUID namespace` reported an assumption violation inside its expected-exception assertion. Both avatar test classes passed in that run.

The fixture-only endpoint returns an internal key so the test can read the object back. Production route authentication, the database reference swap, race and retry behavior, and old-object cleanup belong to T119. The T117 fixture opened no database connection and applied no migrations.

# T116 avatar storage port acceptance

Backend baseline `660b8b9e803155149a2a8a9f129c251d265ebc92` on `feat/profile-avatar-foundation`. The worktree contains Flyway V001–V015. The isolated fixture uses real temporary directories on Windows 10.0.26200.0 and JDK 17.0.10. It exercises storage and the profile staging seam without opening a database connection.

The fixture ran on 2026-11-07 with `gradlew.bat --offline --no-daemon --max-workers=1 "-Dorg.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=512m" -Pkotlin.compiler.execution.strategy=in-process test --tests com.example.storage.LocalAvatarStoreTest --rerun`. The final run passed seven tests with zero failures, errors, or skips.

- Saving equal bytes twice and saving the same bytes under a second supported type generated three distinct UUID v4 keys. Reopening the same directory preserved the expected bytes and type for each key.
- Deleting an older object twice left it absent. The newer object remained readable and was the only directory entry.
- Absolute and relative traversal paths and a noncanonical UUID were rejected on read and delete. The unrelated file retained its contents.
- A regular file configured as the root caused save, read, and delete to fail without altering it. Invalid metadata and oversized records failed on read. Empty and oversized images were rejected. An unrelated directory named like a valid key survived a failed delete.
- `ProfileService` staged bytes via the injected adapter. A missing adapter and a misconfigured local adapter both returned the application error `503 AVATAR_STORAGE_UNAVAILABLE`. The test database boundary rejects any attempt to open a transaction.
- A Windows root granting another principal inheritable read access was rejected on save, read and delete. The root remained empty, and an unprovisioned Windows root was rejected without creation.
- A stored file in a private Windows root was granted read access for another principal. The adapter rejected read and delete. The file remained in place.

Before implementation the new fixture failed to compile because the port and profile seam were absent. A subsequent negative test exposed that delete could remove an empty directory named like a valid object key. The final run verified that delete checks for a regular object before removing it. The first version also accepted a Windows root with an inheritable read grant for another principal. The ACL fixture reproduced the unsafe save before the fix.

`AvatarStore.save` accepts processed JPEG or PNG bytes up to 5 MiB and returns an internal key. `read` returns those bytes and the stored type, or null for a missing object. `delete` accepts an already missing object as success. The local adapter writes a staging file with its type marker and publishes the complete record by creating a hard link with no replacement. Local composition selects `.local/avatars`, excluded from Git and the backend Docker context. The filesystem must support hard links.

## Local directory setup

Run the backend from a trusted workspace outside static file roots. Its path and the path of `.local/avatars` must be canonical, without symlink or junction components. The service account and trusted administrators must control the ancestors so other accounts cannot replace a validated directory. The adapter resolves the service principal from the JVM account name and checks ownership of the directory and objects. It creates a missing POSIX directory with mode `0700` and rejects group or other access to directories and files. On Windows it requires a pre-provisioned NTFS directory with an inheritable owner-only ACL, and checks the ACL of each staged or existing file. Missing or unsafe configuration fails as unavailable.

On Windows, run this in PowerShell from the backend checkout as the same account that will run the backend. The command prepares a fresh directory and stops if the target already exists.

```powershell
$avatarDir = Join-Path (Get-Location) '.local\avatars'
if (Test-Path -LiteralPath $avatarDir) { throw 'Use a fresh avatar directory' }
New-Item -ItemType Directory -Path $avatarDir | Out-Null
$ownerSid = [System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value
icacls $avatarDir /inheritance:r /grant:r "*${ownerSid}:(OI)(CI)F"
if ($LASTEXITCODE -ne 0) { throw 'Avatar directory ACL provisioning failed' }
```

The ACL command ran on a newly created empty Windows temporary directory. The resulting ACL contained one explicit inheritable rule granting the service owner full control.

T117 owns multipart validation, decoding and re-encoding before staging. T118 owns the production adapter and live private bucket evidence for BG-1. T119 owns the database swap and cleanup of unreferenced objects, including a staged object whose key is lost if a request is cancelled or a process stops before the database update. T123 owns deletion worker wiring. The fixture covers storage operations and safe application errors. HTTP authorization, database migrations and the external bucket remain outside this evidence.

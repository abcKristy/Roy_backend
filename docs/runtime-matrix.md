# M1.T1 runtime matrix

Recorded on 2026-11-01 for the backend repository.

| Component | Pinned version | Source |
| --- | --- | --- |
| Gradle Wrapper | 9.5.1 | `gradle/wrapper/gradle-wrapper.properties` |
| Kotlin JVM plugin | 2.4.20 | `gradle/libs.versions.toml` |
| Ktor plugin and server libraries | 3.6.1 | `gradle/libs.versions.toml` |
| Logback | 1.6.3 | `gradle/libs.versions.toml` |
| Java toolchain/target | 17 | `build.gradle.kts` |

The imported starter requested Java 21 while the earlier environment plan
specified Java 17. Java 17 is selected for the backend because an installed
JDK 17 is available on the development machine and it matches that plan.
Gradle, Kotlin, and Ktor versions remain those of the imported starter; this
step does not upgrade or downgrade them. A JDK 17 Gradle JVM and toolchain
are required for the reproducible local build. Set `JAVA_HOME` to an installed
JDK 17 and add its `bin` directory to `PATH`; do not commit a machine-specific
absolute path.

The original Ktor version catalog was fetched by a version literal in
`settings.gradle.kts`. Its used plugin and libraries now resolve from the
local catalog, so their requested versions are visible in one place. The
Gradle distribution version remains in Wrapper metadata, as required by the
Wrapper itself. Catalog versions are requested versions, not a dependency
lock.

Verification: on Windows, with `JAVA_HOME` set to the installed JDK 17,
`.\gradlew.bat compileKotlin --no-daemon` completed successfully (`BUILD
SUCCESSFUL`, 2026-11-01). The Wrapper distribution and dependencies needed
network access on this machine for the first run. This confirms compilation
of the current server sources, not runtime behavior or database integration.

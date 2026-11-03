# Acceptance evidence retry

Date: 2026-11-04. Source baseline: e12ec33, branch fix/acceptance-evidence.
Scope: the fifteen tasks with insufficient evidence in the T001–T056 review.
All database runs use newly provisioned, owned, loopback-only disposable PostGIS
instances. Production .env files and shared databases are not used.
An executed test requires exit code zero and XML with zero failures/errors/skips.
External approval and deployment are never inferred from a local test.

## T002 — Runtime and build

`installDist runMigrations --offline --daemon --max-workers=1 --console=plain`
completed successfully using JDK 17. Every application class in the installed
application JAR has major version 61 (Java 17), independently read from the class
headers. The source uses jvmToolchain(17), catalog aliases and the pinned Wrapper.
runtime-matrix.md now documents the IDE Gradle JVM selector separately from the
compilation target, without committing a personal path. Actual IDE preferences
on another developer's machine are not a prerequisite or a measured result.

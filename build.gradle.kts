
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktor)
}

group = "com.example"
version = "1.0.0-SNAPSHOT"

application {
    mainClass = "io.ktor.server.netty.EngineMain"
}

kotlin {
    jvmToolchain(17)
}

// cmd.exe limits expanded command lines to 8191 characters. Java expands the
// distribution lib wildcard itself, including when the checkout path is long.
tasks.startScripts {
    doLast {
        windowsScript.writeText(windowsScript.readText().replace(
            Regex("(?m)^set CLASSPATH=.*$"),
        ) { "set CLASSPATH=%APP_HOME%\\lib\\*" })
    }
}

dependencies {
    implementation(libs.swagger.ui)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.websockets)
    implementation(libs.logback.classic)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    implementation(libs.postgresql.jdbc)
    implementation(libs.hikari)
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.password4j)
    implementation(libs.java.jwt)

    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.server.testHost)
    testImplementation(libs.ktor.client.websockets)
}

tasks.register<JavaExec>("benchmarkPasswordHashing") {
    group = "verification"
    description = "Measure sequential Argon2id hash and verify time on this host"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.example.auth.Argon2BenchmarkKt")
}

tasks.register<JavaExec>("runMigrations") {
    group = "database"
    description = "Apply Flyway migrations using dedicated migration credentials"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.example.persistence.MigrationMainKt")
}

val verifyApiDocs by tasks.registering(Exec::class) {
    group = "verification"
    description = "Validate published OpenAPI, route coverage and request/response examples (Python 3 required)"
    commandLine("python", "tools/api_docs.py", "check")
}

tasks.check { dependsOn(verifyApiDocs) }

tasks.processResources {
    from("docs/openapi.json") { into("api-docs") }
    from("docs/websocket-api.md") { into("api-docs") }
    from("docs/api-contract-differences.md") { into("api-docs") }
    inputs.property("swaggerUiVersion", libs.versions.swagger.ui.get())
    filesMatching("api-docs/index.html") {
        filter { it.replace("@SWAGGER_UI_VERSION@", libs.versions.swagger.ui.get()) }
    }
    from("docs/privacy-policy-draft.md") {
        into("legal")
    }
}

// A required integration run must never succeed by skipping its database scenarios.
tasks.register<Test>("integrationTest") {
    group = "verification"
    description = "Run explicitly provisioned disposable database scenarios (missing database fails)"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    filter { includeTestsMatching("*IntegrationTest") }
    systemProperty("integration.required", "true")
    afterTest(KotlinClosure2<TestDescriptor, TestResult, Unit>({ _, result ->
        if (result.resultType == TestResult.ResultType.SKIPPED) {
            throw GradleException("Required integration scenario was skipped")
        }
    }))
}

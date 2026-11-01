
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
dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.server.status.pages)
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
}

tasks.register<JavaExec>("benchmarkPasswordHashing") {
    group = "verification"
    description = "Measure sequential Argon2id hash and verify time on this host"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.example.auth.Argon2BenchmarkKt")
}

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    application
}

// The backend for the client and guest views (docs/plans/backend-client-guest.md): one Ktor modular monolith plus a
// worker started from the same code. Flyway owns the schema; Exposed (DSL only) reads and writes it.
application {
    mainClass.set("com.trainingapp.server.ApplicationKt")
}

kotlin {
    compilerOptions {
        optIn.addAll("kotlin.uuid.ExperimentalUuidApi", "kotlin.time.ExperimentalTime")
    }
}

dependencies {
    implementation(projects.contract)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.contentNegotiation)
    implementation(libs.ktor.server.statusPages)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.authJwt)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.rateLimit)
    implementation(libs.ktor.serialization.json)

    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.javaTime)
    implementation(libs.exposed.json)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    implementation(libs.postgresql)
    implementation(libs.hikari)
    implementation(libs.logback)

    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.server.testHost)
    testImplementation(libs.ktor.client.contentNegotiation)
    testImplementation(libs.embeddedPostgres)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    useJUnitPlatform()
    // Integration tests start one PostgreSQL per JVM; keep them in one fork.
    maxParallelForks = 1
    systemProperty("user.timezone", "UTC")
}

// `./gradlew :server:runWorker` starts only the worker (outbox, jobs, media checks).
tasks.register<JavaExec>("runWorker") {
    group = "application"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.trainingapp.server.WorkerMainKt")
}

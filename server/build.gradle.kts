import java.util.Properties

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    application
}

// One Ktor modular monolith for every view: the client/guest API (`/v1`, docs/plans/backend-client-guest.md) and the
// trainer API (`/api/v1`, docs/features/trainer-backend.md), plus a worker started from the same code. Plain JVM:
// builds with -Ptrainer.ui=false, no Android SDK needed. Flyway owns the schema; Exposed (DSL only) reads and writes it.
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
    implementation(libs.ktor.server.callLogging)
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
    // The trainer API tests (TrainerTestSupport) run PostgreSQL in Docker.
    testImplementation(libs.testcontainers.postgresql)
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

// Sign-in settings for `run` and `runWorker` from local.properties (not committed), the same file the app reads
// (docs/plans/firebase-auth.md §8). An environment variable set in the terminal always wins.
//   trainer.auth=dev | firebase              → AUTH_MODE (dev, firebase)
//   firebase.projectId=<Project ID>          → FIREBASE_PROJECT_ID for `firebase`
val serverLocalProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val serverAuthEnvironment: Map<String, String> = buildMap {
    val mode = when (serverLocalProperties.getProperty("trainer.auth")?.trim()?.lowercase()) {
        "firebase" -> "firebase"
        else -> null
    }
    if (mode != null) put("AUTH_MODE", mode)
    val projectId = when (mode) {
        "firebase" -> serverLocalProperties.getProperty("firebase.projectId")?.trim()?.takeIf { it.isNotEmpty() }
        else -> null
    }
    if (projectId != null) put("FIREBASE_PROJECT_ID", projectId)
}.filterKeys { System.getenv(it).isNullOrBlank() }

tasks.withType<JavaExec>().configureEach {
    if (name == "run" || name == "runWorker") {
        environment(serverAuthEnvironment)
        doFirst {
            val firebaseMode = (System.getenv("AUTH_MODE") ?: serverAuthEnvironment["AUTH_MODE"]) == "firebase"
            if (firebaseMode && System.getenv("FIREBASE_PROJECT_ID").isNullOrBlank() && serverAuthEnvironment["FIREBASE_PROJECT_ID"] == null) {
                logger.warn("AUTH_MODE=firebase without firebase.projectId in local.properties: every Firebase token will be rejected.")
            }
        }
    }
}

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    application
}

// Ktor modular monolith (architecture §5–§6). Plain JVM: builds with -Ptrainer.ui=false, no Android SDK needed.
application {
    mainClass.set("com.trainingapp.server.ApplicationKt")
}

kotlin {
    compilerOptions {
        optIn.addAll("kotlin.time.ExperimentalTime", "kotlin.uuid.ExperimentalUuidApi")
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
    implementation(libs.ktor.serialization.json)
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.javaTime)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    implementation(libs.postgresql)
    implementation(libs.hikari)
    implementation(libs.logback)

    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.server.testHost)
    testImplementation(libs.ktor.client.contentNegotiation)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    useJUnitPlatform()
}

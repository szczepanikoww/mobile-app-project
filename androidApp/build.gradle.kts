import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

// Sign-in settings (docs/plans/firebase-auth.md §8): a Gradle property wins, then local.properties (not committed).
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun setting(name: String, default: String = ""): String =
    providers.gradleProperty(name).orNull ?: localProperties.getProperty(name) ?: default
fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "pl.trainerapp"
    compileSdk = 35
    defaultConfig {
        applicationId = "pl.trainerapp"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        // `-Ptrainer.backend=local` (the emulator reaches this computer at 10.0.2.2) or a URL; default: demo data.
        // The same server serves the client/guest API (/v1) and the trainer API (/api/v1).
        buildConfigField("String", "TRAINER_BACKEND", "\"${providers.gradleProperty("trainer.backend").getOrElse("demo")}\"")
        // `-Ptrainer.auth=dev` (default), or `firebase` (needs firebase.apiKey).
        buildConfigField("String", "TRAINER_AUTH", quoted(setting("trainer.auth", "dev")))
        buildConfigField("String", "FIREBASE_API_KEY", quoted(setting("firebase.apiKey")))
        buildConfigField("String", "FIREBASE_WEB_CLIENT_ID", quoted(setting("firebase.webClientId")))
    }
    buildTypes {
        // Plain HTTP only to a local development server, only in debug builds.
        getByName("debug") { manifestPlaceholders["networkSecurityConfig"] = "@xml/network_security_config_debug" }
        getByName("release") { manifestPlaceholders["networkSecurityConfig"] = "@xml/network_security_config" }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        // Session times are kotlin.time.Instant, as in :shared.
        optIn.add("kotlin.time.ExperimentalTime")
    }
}
dependencies {
    implementation(project(":shared:app"))
    implementation(project(":shared:data"))
    implementation(libs.koin.android)
    implementation(libs.sqldelight.androidDriver)
    implementation(libs.kotlinx.coroutines.android)

    implementation(project(":shared"))
    implementation(libs.activity.compose)
    // HTTP engine for the backend and Firebase clients (HttpEngines).
    implementation(libs.ktor.client.okhttp)
    // Google sign-in (docs/plans/firebase-auth.md §9).
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.playServices)
    implementation(libs.googleid)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.compose.ui:ui-tooling-preview:1.9.4")
    debugImplementation("androidx.compose.ui:ui-tooling:1.9.4")
    testImplementation("junit:junit:4.13.2")
    testImplementation(libs.coroutines.test)
}

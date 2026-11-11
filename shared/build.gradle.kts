plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    compilerOptions {
        optIn.add("kotlin.time.ExperimentalTime")
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
    androidTarget()
    jvm("desktop")
    jvmToolchain(17)
    sourceSets {
        commonMain.dependencies {
            api(compose.runtime)
            api(compose.foundation)
            api(compose.material3)
            api(compose.materialIconsExtended)
            implementation(compose.components.resources)
            // Media state for technique videos in the chat; money, cancellation policy rules and locale formats.
            api(project(":shared:domain"))
            implementation(libs.coroutines.core)
            implementation(libs.datetime)
            implementation(libs.lifecycle.viewmodel.compose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            // Trainer API client (docs/features/trainer-backend.md); the engine comes from each platform below.
            api(libs.ktor.client.core)
            implementation(libs.ktor.client.contentNegotiation)
            implementation(libs.ktor.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        androidMain.dependencies {
            implementation(libs.activity.compose)
            implementation(compose.preview)
            implementation(libs.ktor.client.okhttp)
        }
        named("desktopMain").dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.coroutines.swing)
            implementation(libs.ktor.client.java)
        }
        named("desktopTest").dependencies { implementation(compose.desktop.uiTestJUnit4) }
    }
}

android {
    namespace = "pl.trainer.shared"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

compose.resources { packageOfResClass = "pl.trainer.resources" }

tasks.withType<Test>().configureEach {
    systemProperty("user.language", "pl")
    systemProperty("user.country", "PL")
}

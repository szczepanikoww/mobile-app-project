plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.android.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Client workout logging, summary and progress: A1–A3 from docs/features/workout-logging-video.md.
kotlin {
    androidTarget()
    jvm("desktop")

    sourceSets {
        all {
            languageSettings.optIn("kotlin.uuid.ExperimentalUuidApi")
            languageSettings.optIn("kotlinx.coroutines.ExperimentalCoroutinesApi")
            languageSettings.optIn("androidx.compose.material3.ExperimentalMaterial3Api")
            languageSettings.optIn("androidx.compose.foundation.layout.ExperimentalLayoutApi")
            languageSettings.optIn("org.jetbrains.compose.resources.ExperimentalResourceApi")
            languageSettings.optIn("kotlin.time.ExperimentalTime")
        }
        commonMain.dependencies {
            implementation(projects.shared.designsystem)
            implementation(libs.compose.components.resources)
            implementation(projects.shared.domain)
            // Reuses the exercise photo, labels, demo-video card and the platform video picker.
            implementation(projects.shared.feature.workoutBuilder)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.androidx.navigation.compose)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.composeViewmodel)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        val desktopTest by getting {
            dependencies { implementation(libs.kotlinx.coroutines.swing) }
        }
    }
}

android {
    namespace = "com.trainingapp.feature.workoutlogging"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.android.minSdk.get().toInt() }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

compose.resources {
    packageOfResClass = "com.trainingapp.feature.workoutlogging.resources"
}

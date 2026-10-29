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
            implementation(libs.coroutines.core)
            implementation(libs.datetime)
            implementation(libs.lifecycle.viewmodel.compose)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
        androidMain.dependencies { implementation(libs.activity.compose) }
        named("desktopMain").dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.coroutines.swing)
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

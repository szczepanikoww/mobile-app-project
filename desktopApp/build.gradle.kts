plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

// A development preview at phone size, for teammates without an emulator. Android is the product target.
dependencies {
    implementation(projects.shared.app)
    implementation(projects.shared.data)
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.sqldelight.sqliteDriver)
}

compose.desktop {
    application {
        mainClass = "com.trainingapp.desktop.MainKt"
    }
}

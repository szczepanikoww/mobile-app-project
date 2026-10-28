// Plugins go on the root build script classpath so the Kotlin and Android plugins share one class loader.
// The UI plugins are added only when the Compose/Android modules are part of the build (see settings.gradle.kts).
buildscript {
    val includeUi = (findProperty("trainer.ui") as String?)?.toBooleanStrictOrNull() ?: true
    repositories {
        gradlePluginPortal()
        mavenCentral()
        if (includeUi) google()
    }
    dependencies {
        classpath(libs.kotlin.gradlePlugin)
        classpath(libs.kotlin.serializationPlugin)
        classpath(libs.sqldelight.gradlePlugin)
        if (includeUi) {
            classpath(libs.android.gradlePlugin)
            classpath(libs.compose.gradlePlugin)
            classpath(libs.kotlin.composeCompilerPlugin)
        }
    }
}

// One JVM bytecode level for every module: Android, desktop, tests and a future JVM backend.
subprojects {
    // Android modules set Java 17 through compileOptions; AGP does not accept --release.
    tasks.withType<JavaCompile>().configureEach {
        if (!project.plugins.hasPlugin("com.android.base")) options.release.set(17)
    }
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
        compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

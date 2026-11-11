plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}
kotlin {
    jvmToolchain(17)
    compilerOptions { optIn.add("kotlin.time.ExperimentalTime") }
}
dependencies {
    implementation(project(":shared:app"))
    implementation(project(":shared:data"))
    implementation(libs.sqldelight.sqliteDriver)

    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(libs.coroutines.swing)
    implementation(libs.lifecycle.viewmodel.compose)
}
// `-Ptrainer.backend=local` (or a URL) connects the app to the backend; the default is the demo data.
val trainerBackend = providers.gradleProperty("trainer.backend").orElse("demo")

compose.desktop {
    application {
        mainClass = "com.trainingapp.desktop.MainKt"
        jvmArgs += listOf("-Dtrainer.backend=${trainerBackend.get()}")
    }
}

tasks.register<JavaExec>("runPreview") {
    group = "application"
    description = "Run the app with a separate window for preview scenarios."
    dependsOn("classes")
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("pl.trainer.desktop.MainKt")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) })
    systemProperty("trainer.preview", "true")
    systemProperty("trainer.backend", trainerBackend.get())
}

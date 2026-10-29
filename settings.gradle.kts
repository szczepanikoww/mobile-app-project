pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositories { google(); mavenCentral() }
}
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
rootProject.name = "TrainerApp"
val includeUi = providers.gradleProperty("trainer.ui").orNull?.toBooleanStrictOrNull() ?: true
if (file("contract/build.gradle.kts").exists()) {
    include(":contract", ":shared:domain", ":shared:data")
    if (!includeUi) project(":shared").buildFileName = "headless.gradle.kts"
}
if (includeUi) {
    include(":shared", ":androidApp")
    if (file("desktopApp/build.gradle.kts").exists()) include(":desktopApp")
    if (file("shared/app/build.gradle.kts").exists()) {
        include(":shared:designsystem", ":shared:feature:workout-builder", ":shared:app")
    }
}

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
    include(":contract", ":shared:domain", ":shared:data", ":server")
    if (!includeUi) project(":shared").buildFileName = "headless.gradle.kts"
    // The Ktor backend (docs/plans/backend-client-guest.md). JVM only; shares DTOs and rules through :contract.
    if (file("server/build.gradle.kts").exists()) include(":server")
}
if (includeUi) {
    include(":shared", ":androidApp")
    if (file("desktopApp/build.gradle.kts").exists()) include(":desktopApp")
    if (file("shared/app/build.gradle.kts").exists()) {
        include(
            ":shared:designsystem", ":shared:feature:workout-builder", ":shared:feature:guest",
            ":shared:feature:workout-logging", ":shared:feature:client", ":shared:app",
        )
    }
}

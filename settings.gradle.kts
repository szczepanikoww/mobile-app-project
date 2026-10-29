pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositories { google(); mavenCentral() }
}
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
rootProject.name = "TrainerApp"
include(":shared", ":androidApp")
// Feature branches add their modules without replacing the common Android host or theme.
if (file("desktopApp/build.gradle.kts").exists()) include(":desktopApp")
if (file("contract/build.gradle.kts").exists()) {
    include(":contract", ":shared:domain", ":shared:data", ":shared:designsystem", ":shared:feature:workout-builder", ":shared:app")
}

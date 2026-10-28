rootProject.name = "mobile-app-project"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

// Pure Kotlin modules build anywhere. The Compose/Android modules need Google Maven and the Android SDK.
val includeUi = providers.gradleProperty("trainer.ui").orNull?.toBooleanStrictOrNull() ?: true

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (providers.gradleProperty("trainer.ui").orNull?.toBooleanStrictOrNull() != false) {
            google {
                content {
                    includeGroupByRegex("androidx.*")
                    includeGroupByRegex("com\\.android.*")
                    includeGroupByRegex("com\\.google\\.android.*")
                    includeGroup("com.google.testing.platform")
                }
            }
        }
        mavenCentral()
    }
}

include(":contract")
include(":shared:domain")
include(":shared:data")

if (includeUi) {
    include(":shared:designsystem")
    include(":shared:feature:workout-builder")
    include(":shared:app")
    include(":androidApp")
    include(":desktopApp")
}

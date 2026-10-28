plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("app.cash.sqldelight")
}

// Repositories, the SQLDelight database and the fake remotes used until the backend exists.
// Platform drivers are created by the app (AndroidSqliteDriver / JdbcSqliteDriver).
kotlin {
    jvm()

    sourceSets {
        all { languageSettings.optIn("kotlin.uuid.ExperimentalUuidApi") }
        commonMain.dependencies {
            api(projects.shared.domain)
            api(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            implementation(libs.sqldelight.sqliteDriver)
        }
    }
}

sqldelight {
    databases {
        create("TrainingDatabase") {
            packageName.set("com.trainingapp.data.db")
        }
    }
}

// Lets a test parse the real bundled seed file.
tasks.withType<Test>().configureEach {
    systemProperty(
        "exerciseSeedPath",
        rootProject.file("shared/app/src/commonMain/composeResources/files/exercise_library.json").absolutePath,
    )
}

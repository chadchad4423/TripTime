pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Mudita Mindful Design (MMD) has been published from more than one location as the
        // library matured. InkWeather (this account's other Kompakt app) resolves
        // com.mudita:MMD:1.0.2 from mavenCentral() alone, but the community CalmDirectory
        // app needed this JFrog repository for an earlier MMD release. Keeping it here is a
        // harmless fallback: Gradle only uses it if the artifact isn't found above.
        maven { url = uri("https://mudita.jfrog.io/artifactory/mmd-release") }
    }
}

rootProject.name = "TripTime"
include(":app")

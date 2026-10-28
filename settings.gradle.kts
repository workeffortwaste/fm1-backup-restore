// Android tooling is published in Google Maven; Kotlin plugins also use the Gradle plugin portal.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
// Gradle still marks the settings-level repository policy API as incubating.
@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    // Keep all library repositories visible here rather than allowing module-specific surprises.
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "FM-1 backup and restore"
include(":app")

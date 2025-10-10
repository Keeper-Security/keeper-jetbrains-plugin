rootProject.name = "keeper-jetbrains-plugin"

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// Enable version catalogs (should be enabled by default in modern Gradle)
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
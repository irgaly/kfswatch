enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version ("1.0.0")
}
rootProject.name = "kfswatch-project"
include(
    ":kfswatch",
    ":test",
    ":sample:android"
)
includeBuild("build-logic")

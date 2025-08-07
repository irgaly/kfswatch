plugins {
    `kotlin-dsl`
}
dependencies {
    implementation(libs.gradle.android)
    implementation(libs.gradle.kotlin)
}
gradlePlugin {
    plugins {
        register("kotlin.multiplatform") {
            id = libs.plugins.buildlogic.multiplatform.library.get().pluginId
            implementationClass = "io.github.irgaly.buildlogic.MultiplatformLibraryPlugin"
        }
        register("android.application") {
            id = libs.plugins.buildlogic.android.application.get().pluginId
            implementationClass = "io.github.irgaly.buildlogic.AndroidApplicationPlugin"
        }
        register("android.library") {
            id = libs.plugins.buildlogic.android.library.get().pluginId
            implementationClass = "io.github.irgaly.buildlogic.AndroidLibraryPlugin"
        }
    }
}
kotlin {
    jvmToolchain(21)
}

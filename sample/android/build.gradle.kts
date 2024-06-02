plugins {
    alias(libs.plugins.buildlogic.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "io.github.irgaly.kfswatch.sample"
    defaultConfig {
        applicationId = "io.github.irgaly.kfswatch.sample"
        versionName = libs.versions.kfswatch.get()
        minSdk = 21
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(dependencies.platform(libs.compose.bom))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle)
    implementation(libs.bundles.compose)
    implementation(projects.kfswatch)
}

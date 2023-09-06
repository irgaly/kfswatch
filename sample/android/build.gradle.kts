plugins {
    alias(libs.plugins.buildlogic.android.application)
}

android {
    namespace = "io.github.irgaly.kfswatch.sample"
    defaultConfig {
        applicationId = "io.github.irgaly.kfswatch.sample"
        versionName = libs.versions.kfswatch.get()
        minSdk = 21
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.compose.compiler.get()
    }
}

kotlin {
    jvmToolchain(11)
}

dependencies {
    implementation(dependencies.platform(libs.compose.bom))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle)
    implementation(libs.bundles.compose)
    implementation(projects.kfswatch)
}

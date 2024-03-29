plugins {
    alias(libs.plugins.buildlogic.multiplatform.library)
    alias(libs.plugins.buildlogic.android.library)
}

android {
    namespace = "io.github.irgaly.test"
}

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                implementation(libs.test.kotest.engine)
            }
        }
        val androidMain by getting {
            dependencies {
                implementation(libs.test.android.runner)
            }
        }
    }
}

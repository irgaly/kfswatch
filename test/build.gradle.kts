plugins {
    alias(libs.plugins.buildlogic.multiplatform.library)
    alias(libs.plugins.buildlogic.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotest)
}

kotlin {
    android {
        namespace = "io.github.irgaly.test"
    }
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

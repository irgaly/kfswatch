import com.android.build.api.dsl.ManagedVirtualDevice
import org.jetbrains.dokka.gradle.DokkaTask

plugins {
    alias(libs.plugins.buildlogic.multiplatform.library)
    alias(libs.plugins.buildlogic.android.library)
    alias(libs.plugins.dokka)
    alias(libs.plugins.android.junit5)
}

kotlin {
    mingwX64 {
        binaries.configureEach {
            // UuidCreate, RpcStringFreeW, UuidToStringW に必要
            linkerOpts("-lrpcrt4")
        }
    }
    sourceSets {
        commonMain {
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        commonTest {
            dependencies {
                implementation(projects.test)
            }
        }
        val androidInstrumentedTest by getting {
            dependsOn(commonTest.get())
            dependencies {
                implementation(libs.bundles.test.android.instrumented)
            }
        }
    }
}

dependencies {
    androidTestRuntimeOnly(libs.test.android.junit5.runner)
}

android {
    namespace = "io.github.irgaly.kfswatch"
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["runnerBuilder"] =
            "de.mannodermaus.junit5.AndroidJUnit5Builder"
    }
    testOptions {
        managedDevices {
            val pixel6android13 by devices.registering(ManagedVirtualDevice::class) {
                device = "Pixel 6"
                apiLevel = 33 // Android 13
            }
            val pixel6android8 by devices.registering(ManagedVirtualDevice::class) {
                device = "Pixel 6"
                apiLevel = 27 // Android 8
            }
            groups {
                register("pixel6") {
                    targetDevices.addAll(listOf(pixel6android13.get(), pixel6android8.get()))
                }
            }
        }
    }
}

val dokkaHtml by tasks.getting(DokkaTask::class)
val javadocJar by tasks.registering(Jar::class) {
    dependsOn(dokkaHtml)
    from(dokkaHtml.outputDirectory)
    archiveClassifier = "javadoc"
}

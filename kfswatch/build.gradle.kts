import com.android.build.api.dsl.ManagedVirtualDevice
import org.jetbrains.dokka.gradle.tasks.DokkaGeneratePublicationTask
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsPlugin.Companion.kotlinNodeJsEnvSpec

plugins {
    alias(libs.plugins.buildlogic.multiplatform.library)
    alias(libs.plugins.buildlogic.android.library)
    alias(libs.plugins.dokka)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotest)
}

kotlin {
    mingwX64 {
        binaries.configureEach {
            // UuidCreate, RpcStringFreeW, UuidToStringW に必要
            linkerOpts("-lrpcrt4")
        }
    }
    applyDefaultHierarchyTemplate()
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
        }
    }
}

kotlinNodeJsEnvSpec.apply {
    version = "24.9.0"
}

android {
    namespace = "io.github.irgaly.kfswatch"
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

val dokkaGeneratePublicationHtml by tasks.getting(DokkaGeneratePublicationTask::class)
val javadocJar by tasks.registering(Jar::class) {
    from(dokkaGeneratePublicationHtml.outputDirectory)
    archiveClassifier = "javadoc"
}

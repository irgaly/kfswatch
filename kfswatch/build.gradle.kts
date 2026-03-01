import org.jetbrains.dokka.gradle.tasks.DokkaGeneratePublicationTask
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsPlugin.Companion.kotlinNodeJsEnvSpec

plugins {
    alias(libs.plugins.buildlogic.multiplatform.library)
    alias(libs.plugins.buildlogic.android.library)
    alias(libs.plugins.dokka)
    alias(libs.plugins.android.junit5)
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
    android {
        namespace = "io.github.irgaly.kfswatch"
        withDeviceTest {
            instrumentationRunnerArguments["runnerBuilder"] =
                "de.mannodermaus.junit5.AndroidJUnit5Builder"
            managedDevices {
                localDevices {
                    val pixel6android13 by registering {
                        device = "Pixel 6"
                        apiLevel = 33 // Android 13
                    }
                    val pixel6android8 by registering {
                        device = "Pixel 6"
                        apiLevel = 27 // Android 8
                    }
                }
                groups {
                    register("pixel6") {
                        targetDevices.addAll(
                            listOf(
                                localDevices["pixel6android13"],
                                localDevices["pixel6android8"],
                            )
                        )
                    }
                }
            }
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
        val androidDeviceTest by getting {
            dependsOn(commonTest.get())
            dependencies {
                implementation(libs.bundles.test.android.instrumented)
                runtimeOnly(libs.test.android.junit5.runner)
            }
        }
    }
}

kotlinNodeJsEnvSpec.apply {
    version = "24.9.0"
}

val dokkaGeneratePublicationHtml by tasks.getting(DokkaGeneratePublicationTask::class)
val javadocJar by tasks.registering(Jar::class) {
    from(dokkaGeneratePublicationHtml.outputDirectory)
    archiveClassifier = "javadoc"
}

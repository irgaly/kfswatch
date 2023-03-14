package io.github.irgaly.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.CommonExtension
import com.android.build.gradle.BaseExtension
import com.android.build.gradle.LibraryExtension
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.*
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import java.io.ByteArrayOutputStream

/**
 * android build 共通設定を適用する
 */
fun Project.configureAndroid() {
    extensions.configure<BaseExtension> {
        (this as CommonExtension<*, *, *, *>).apply {
            compileSdk = libs.version("gradle-android-compile-sdk").toInt()
            defaultConfig {
                minSdk = libs.version("gradle-android-min-sdk").toInt()
            }
            if (this is ApplicationExtension) {
                defaultConfig {
                    targetSdk = libs.version("gradle-android-target-sdk").toInt()
                }
            }
        }
    }
}

/**
 * android library 共通設定を適用する
 */
fun Project.configureAndroidLibrary() {
    extensions.configure<LibraryExtension> {
        buildFeatures {
            buildConfig = false
        }
        sourceSets.configureEach {
            java.srcDirs("src/$name/kotlin")
        }
    }
}

/**
 * VersionCatalog の取得
 */
val Project.libs: VersionCatalog
    get() {
        return extensions.getByType<VersionCatalogsExtension>().named("libs")
    }

/**
 * VersionCatalog version の取得
 */
fun VersionCatalog.version(name: String): String {
    return findVersion(name).get().requiredVersion
}

/**
 * VersionCatalog pluginId の取得
 */
fun VersionCatalog.pluginId(name: String): String {
    return findPlugin(name).get().get().pluginId
}

/**
 * Execute shell command
 */
fun Project.execute(vararg commands: String): String {
    val out = ByteArrayOutputStream()
    exec {
        commandLine = listOf("sh", "-c") + commands
        standardOutput = out
        isIgnoreExitValue = true
    }
    return out.toString().trim()
}

/**
 * multiplatform library 共通設定
 */
@Suppress("UNUSED_VARIABLE")
fun Project.configureMultiplatformLibrary() {
    extensions.configure<KotlinMultiplatformExtension> {
        pluginManager.withPlugin("com.android.library") {
            // Android AAR
            android {
                publishAllLibraryVariants()
            }
        }
        // Java jar
        jvm()
        // iOS
        ios()
        // ios() - iosArm64() // Apple iOS on ARM64 platforms (Apple iPhone 5s and newer)
        // ios() - iosX64() // Apple iOS simulator on x86_64 platforms
        iosSimulatorArm64() // Apple iOS simulator on Apple Silicon platforms
        // watchOS
        watchos()
        // watchos() - watchosArm64() // Apple watchOS on ARM64_32 platforms (Apple Watch Series 4 and newer)
        // watchos() - watchosX64() // Apple watchOS 64-bit simulator (watchOS 7.0 and newer) on x86_64 platforms
        watchosSimulatorArm64() // Apple watchOS simulator on Apple Silicon platforms
        // kotlinx-coroutines-core not supports watchosDeviceArm64
        //watchosDeviceArm64() // Apple watchOS on ARM64 platforms
        // tvOS
        tvos()
        // tvos() - tvosArm64() // Apple tvOS on ARM64 platforms (Apple TV 4th generation and newer)
        // tvos() - tvosX64() // Apple tvOS simulator on x86_64 platforms
        tvosSimulatorArm64() // Apple tvOS simulator on Apple Silicon platforms
        // macOS
        macosX64() // Apple macOS on x86_64 platforms
        macosArm64() // Apple macOS on Apple Silicon platforms
        // Linux
        linuxX64() // Linux on x86_64 platforms
        // Windows
        mingwX64() // 64-bit Microsoft Windows
        // JS
        js(IR) {
            browser()
            nodejs()
        }
        sourceSets {
            val commonMain by getting
            val commonTest by getting
            val nativeMain by creating {
                dependsOn(commonMain)
            }
            val nativeTest by creating {
                dependsOn(nativeMain)
                dependsOn(commonTest)
            }
            val darwinMain by creating {
                dependsOn(nativeMain)
            }
            val darwinTest by creating {
                dependsOn(darwinMain)
                dependsOn(nativeTest)
            }
            val linuxMain by creating {
                dependsOn(nativeMain)
            }
            val linuxTest by creating {
                dependsOn(linuxMain)
                dependsOn(nativeTest)
            }
            val linuxX64Main by getting {
                dependsOn(linuxMain)
            }
            val linuxX64Test by getting {
                dependsOn(linuxTest)
            }
            val iosMain by getting {
                dependsOn(darwinMain)
            }
            val iosTest by getting {
                dependsOn(darwinTest)
            }
            val watchosMain by getting {
                dependsOn(iosMain)
            }
            val watchosTest by getting {
                dependsOn(iosTest)
            }
            val tvosMain by getting {
                dependsOn(iosMain)
            }
            val tvosTest by getting {
                dependsOn(iosTest)
            }
            val iosSimulatorArm64Main by getting {
                dependsOn(iosMain)
            }
            val iosSimulatorArm64Test by getting {
                dependsOn(iosTest)
            }
            val watchosSimulatorArm64Main by getting {
                dependsOn(iosMain)
            }
            val watchosSimulatorArm64Test by getting {
                dependsOn(iosTest)
            }
            /*
            val watchosDeviceArm64Main by getting {
                dependsOn(iosMain)
            }
            val watchosDeviceArm64Test by getting {
                dependsOn(iosTest)
            }
             */
            val tvosSimulatorArm64Main by getting {
                dependsOn(iosMain)
            }
            val tvosSimulatorArm64Test by getting {
                dependsOn(iosTest)
            }
            val macosX64Main by getting {
                dependsOn(darwinMain)
            }
            val macosX64Test by getting {
                dependsOn(darwinTest)
            }
            val macosArm64Main by getting {
                dependsOn(darwinMain)
            }
            val macosArm64Test by getting {
                dependsOn(darwinTest)
            }
            val mingwX64Main by getting {
                dependsOn(nativeMain)
            }
            val mingwX64Test by getting {
                dependsOn(mingwX64Main)
                dependsOn(nativeTest)
            }
        }
    }
}

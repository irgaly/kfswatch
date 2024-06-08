package io.github.irgaly.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.CommonExtension
import com.android.build.gradle.BaseExtension
import com.android.build.gradle.LibraryExtension
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import java.io.ByteArrayOutputStream

/**
 * android build 共通設定を適用する
 */
fun Project.configureAndroid() {
    extensions.configure<BaseExtension> {
        (this as CommonExtension<*, *, *, *, *, *>).apply {
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
        packaging {
            resources {
                excludes.add("META-INF/AL2.0")
                excludes.add("META-INF/LGPL2.1")
                excludes.add("META-INF/licenses/ASM")
                pickFirsts.add("win32-x86-64/attach_hotspot_windows.dll")
                pickFirsts.add("win32-x86/attach_hotspot_windows.dll")
            }
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
@OptIn(ExperimentalKotlinGradlePluginApi::class)
fun Project.configureMultiplatformLibrary() {
    extensions.configure<KotlinMultiplatformExtension> {
        pluginManager.withPlugin("com.android.library") {
            // Android AAR
            androidTarget {
                publishAllLibraryVariants()
            }
        }
        targetHierarchy.default()
        // Java jar
        jvm()
        // iOS
        iosArm64() // Apple iOS on ARM64 platforms (Apple iPhone 5s and newer)
        iosX64() // Apple iOS simulator on x86_64 platforms
        iosSimulatorArm64() // Apple iOS simulator on Apple Silicon platforms
        // watchOS
        watchosArm64() // Apple watchOS on ARM64_32 platforms (Apple Watch Series 4 and newer)
        watchosDeviceArm64() // Apple watchOS on ARM64 platforms (Apple Watch Series 8 and newer)
        watchosX64() // Apple watchOS 64-bit simulator (watchOS 7.0 and newer) on x86_64 platforms
        watchosSimulatorArm64() // Apple watchOS simulator on Apple Silicon platforms
        // tvOS
        tvosArm64() // Apple tvOS on ARM64 platforms (Apple TV 4th generation and newer)
        tvosX64() // Apple tvOS simulator on x86_64 platforms
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
    }
}

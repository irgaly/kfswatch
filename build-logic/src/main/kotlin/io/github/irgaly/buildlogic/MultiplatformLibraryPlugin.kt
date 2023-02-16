package io.github.irgaly.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project

class MultiplatformLibraryPlugin: Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            with(pluginManager) {
                apply("org.jetbrains.kotlin.multiplatform")
                apply(libs.pluginId("kotest-multiplatform"))
            }
            configureMultiplatformLibrary()
        }
    }
}

package io.github.irgaly.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project

class AndroidApplicationPlugin: Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            with(pluginManager) {
                apply(libs.pluginId("android-application"))
            }
            configureAndroid()
        }
    }
}

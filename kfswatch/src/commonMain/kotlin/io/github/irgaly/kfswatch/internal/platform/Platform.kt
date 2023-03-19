package io.github.irgaly.kfswatch.internal.platform

internal expect class Platform {
    companion object {
        val isAndroid: Boolean
        val isIos: Boolean
        val isJvm: Boolean
        val isJvmLinux: Boolean
        val isJvmMacos: Boolean
        val isJvmWindows: Boolean
        val isNodejs: Boolean
        val isNodejsMacos: Boolean
        val isNodejsWindows: Boolean
        val isBrowser: Boolean
        val isLinux: Boolean
        val isWindows: Boolean
        val isMacos: Boolean
    }
}

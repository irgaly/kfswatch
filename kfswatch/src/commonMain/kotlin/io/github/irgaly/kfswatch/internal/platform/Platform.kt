package io.github.irgaly.kfswatch.internal.platform

internal expect class Platform {
    companion object {
        val isAndroid: Boolean
        val isIos: Boolean
        val isJvm: Boolean
        val isJvmMacos: Boolean
        val isNodejs: Boolean
        val isBrowser: Boolean
        val isLinux: Boolean
        val isWindows: Boolean
        val isMacos: Boolean
    }
}

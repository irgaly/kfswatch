package io.github.irgaly.kfswatch.internal.platform

internal actual class Platform {
    actual companion object {
        actual val isAndroid: Boolean = false
        actual val isIos: Boolean = false
        actual val isJvm: Boolean = false
        actual val isJvmMacos: Boolean = false
        actual val isNodejs: Boolean = false
        actual val isNodejsMacos: Boolean = false
        actual val isBrowser: Boolean = false
        actual val isLinux: Boolean = false
        actual val isWindows: Boolean = false
        actual val isMacos: Boolean = true
    }
}

package io.github.irgaly.kfswatch.internal.platform

internal actual class Platform {
    actual companion object {
        actual val isAndroid: Boolean = false
        actual val isIos: Boolean = false
        actual val isJvm: Boolean = true
        actual val isJvmLinux: Boolean = System.getProperty("os.name").startsWith("Linux")
        actual val isJvmMacos: Boolean = System.getProperty("os.name").startsWith("Mac OS")
        actual val isJvmWindows: Boolean = System.getProperty("os.name").startsWith("Windows")
        actual val isNodejs: Boolean = false
        actual val isNodejsLinux: Boolean = false
        actual val isNodejsMacos: Boolean = false
        actual val isNodejsWindows: Boolean = false
        actual val isBrowser: Boolean = false
        actual val isLinux: Boolean = false
        actual val isWindows: Boolean = false
        actual val isMacos: Boolean = false
    }
}

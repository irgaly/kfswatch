package io.github.irgaly.kfswatch.internal.platform

private val fs: dynamic get() = js("require('fs')")

internal actual class Files {
    actual companion object {
        actual fun exists(path: String): Boolean {
            return if (isBrowser()) {
                throw UnsupportedOperationException("browser js cannot access to File Storage")
            } else {
                fs.existsSync(path).unsafeCast<Boolean>()
            }
        }

        actual fun mkdirs(directoryPath: String): Boolean {
            return if (isBrowser()) {
                throw UnsupportedOperationException("browser js cannot access to File Storage")
            } else {
                fs.mkdirSync(directoryPath, js("{recursive: true}"))
                true
            }
        }

        actual val separator: String = "/"
    }
}

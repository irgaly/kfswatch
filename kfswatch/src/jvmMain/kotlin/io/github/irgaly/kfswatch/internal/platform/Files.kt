package io.github.irgaly.kfswatch.internal.platform

import java.io.File

internal actual class Files {
    actual companion object {
        actual fun exists(path: String): Boolean {
            return File(path).exists()
        }

        actual fun mkdirs(directoryPath: String): Boolean {
            return File(directoryPath).mkdirs()
        }

        actual val separator: String = File.separator
    }
}

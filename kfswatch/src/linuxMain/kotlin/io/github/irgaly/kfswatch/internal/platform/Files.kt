package io.github.irgaly.kfswatch.internal.platform

import platform.posix.F_OK
import platform.posix.access
import platform.posix.mkdir

internal actual class Files {
    actual companion object {
        actual fun exists(path: String): Boolean {
            return (access(path, F_OK) == 0)
        }

        actual fun mkdirs(directoryPath: String): Boolean {
            return if (exists(directoryPath)) {
                true
            } else {
                var parentExists = false
                directoryPath.parentDir?.let { parent ->
                    parentExists = exists(parent)
                    if (!parentExists) {
                        parentExists = mkdirs(parent)
                    }
                }
                if (parentExists) {
                    (mkdir(directoryPath, 755) == 0)
                } else false
            }
        }

        actual val separator: String = "/"

        private val String.parentDir: String?
            get() {
                val lastSeparator = lastIndexOf(separator)
                return if (lastSeparator <= 0) {
                    // case: "/...(parent is root directory)" or "(no slash)"
                    null
                } else {
                    take(lastSeparator)
                }
            }
    }
}

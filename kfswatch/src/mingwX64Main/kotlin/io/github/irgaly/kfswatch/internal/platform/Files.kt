package io.github.irgaly.kfswatch.internal.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.windows.ERROR_SUCCESS
import platform.windows.GetFileAttributesW
import platform.windows.INVALID_FILE_ATTRIBUTES
import platform.windows.SHCreateDirectoryExW

internal actual class Files {
    actual companion object {
        actual suspend fun exists(path: String): Boolean = withContext(Dispatchers.Default) {
            val windowsPath = path.replace("/", separator)
            (GetFileAttributesW(windowsPath) != INVALID_FILE_ATTRIBUTES)
        }

        actual suspend fun mkdirs(directoryPath: String): Boolean =
            withContext(Dispatchers.Default) {
                val windowsPath = directoryPath.replace("/", separator)
                (SHCreateDirectoryExW(null, windowsPath, null) == ERROR_SUCCESS)
            }

        actual val separator: String = "\\"
    }
}

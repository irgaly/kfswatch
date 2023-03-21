package io.github.irgaly.kfswatch.internal.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal actual class Files {
    actual companion object {
        actual suspend fun exists(path: String): Boolean = withContext(Dispatchers.IO) {
            File(path).exists()
        }

        actual suspend fun mkdirs(directoryPath: String): Boolean = withContext(Dispatchers.IO) {
            File(directoryPath).mkdirs()
        }

        actual val separator: String = File.separator
    }
}

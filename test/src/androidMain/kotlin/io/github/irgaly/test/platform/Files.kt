package io.github.irgaly.test.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.io.path.createTempDirectory

actual class Files {
    actual companion object {
        actual suspend fun createTemporaryDirectory(): String = withContext(Dispatchers.IO) {
            // https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.io.path/create-temp-directory.html
            // https://docs.oracle.com/javase/jp/8/docs/api/java/nio/file/Files.html#createTempDirectory-java.lang.String-java.nio.file.attribute.FileAttribute...-
            createTempDirectory().toString()
        }

        actual suspend fun createDirectory(path: String): Boolean = withContext(Dispatchers.IO) {
            File(path).mkdir()
        }

        actual suspend fun writeFile(path: String, text: String): Boolean =
            withContext(Dispatchers.IO) {
                val file = File(path)
                val isDirectory = file.isDirectory
                if (isDirectory) {
                    false
                } else {
                    file.writeText(text)
                    true
                }
            }

        actual suspend fun deleteRecursively(path: String): Boolean = withContext(Dispatchers.IO) {
            // https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.io/java.io.-file/delete-recursively.html
            File(path).deleteRecursively()
        }
    }
}

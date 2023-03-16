package io.github.irgaly.test.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.StandardCopyOption
import kotlin.io.path.createTempDirectory

actual class Files {
    actual companion object {
        actual suspend fun createTemporaryDirectory(): String = withContext(Dispatchers.IO) {
            // https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.io.path/create-temp-directory.html
            // https://docs.oracle.com/javase/jp/8/docs/api/java/nio/file/Files.html#createTempDirectory-java.lang.String-java.nio.file.attribute.FileAttribute...-
            // JVM + macOS: /var/folders/.../6089636834939322082
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

        actual suspend fun move(source: String, destination: String): Boolean =
            withContext(Dispatchers.IO) {
                try {
                    java.nio.file.Files.move(
                        source,
                        destination,
                        // destination がファイルまたは空のディレクトリであれば上書きされる
                        StandardCopyOption.REPLACE_EXISTING
                    )
                    true
                } catch (_: DirectoryNotEmptyException) {
                    false
                }
            }


        actual suspend fun deleteRecursively(path: String): Boolean = withContext(Dispatchers.IO) {
            // https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.io/java.io.-file/delete-recursively.html
            File(path).deleteRecursively()
        }
    }
}

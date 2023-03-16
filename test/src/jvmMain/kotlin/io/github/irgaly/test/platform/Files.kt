package io.github.irgaly.test.platform

import java.io.File
import kotlin.io.path.createTempDirectory

actual class Files {
    actual companion object {
        actual fun createTemporaryDirectory(): String {
            // https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.io.path/create-temp-directory.html
            // https://docs.oracle.com/javase/jp/8/docs/api/java/nio/file/Files.html#createTempDirectory-java.lang.String-java.nio.file.attribute.FileAttribute...-
            // JVM + macOS: /var/folders/.../6089636834939322082
            return createTempDirectory().toString()
        }

        actual fun createDirectory(path: String): Boolean {
            return File(path).mkdir()
        }

        actual fun writeFile(path: String, text: String): Boolean {
            val file = File(path)
            val isDirectory = file.isDirectory
            return if (isDirectory) {
                false
            } else {
                file.writeText(text)
                true
            }
        }

        actual fun deleteRecursively(path: String): Boolean {
            // https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.io/java.io.-file/delete-recursively.html
            return File(path).deleteRecursively()
        }
    }
}

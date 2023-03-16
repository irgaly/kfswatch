package io.github.irgaly.test.platform

expect class Files {
    companion object {
        suspend fun createTemporaryDirectory(): String
        suspend fun createDirectory(path: String): Boolean
        suspend fun writeFile(path: String, text: String): Boolean
        suspend fun deleteRecursively(path: String): Boolean
    }
}

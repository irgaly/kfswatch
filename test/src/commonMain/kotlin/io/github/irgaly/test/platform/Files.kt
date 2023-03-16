package io.github.irgaly.test.platform

expect class Files {
    companion object {
        fun createTemporaryDirectory(): String
        fun createDirectory(path: String): Boolean
        fun writeFile(path: String, text: String): Boolean
        fun deleteRecursively(path: String): Boolean
    }
}

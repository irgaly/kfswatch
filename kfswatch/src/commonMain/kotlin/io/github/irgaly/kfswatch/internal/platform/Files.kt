package io.github.irgaly.kfswatch.internal.platform

internal expect class Files {
    companion object {
        suspend fun exists(path: String): Boolean
        suspend fun mkdirs(directoryPath: String): Boolean
        val separator: String
    }
}

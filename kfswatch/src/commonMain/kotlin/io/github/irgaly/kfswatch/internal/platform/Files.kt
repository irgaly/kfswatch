package io.github.irgaly.kfswatch.internal.platform

internal expect class Files {
    companion object {
        fun exists(path: String): Boolean
        fun mkdirs(directoryPath: String): Boolean
        val separator: String
    }
}

package io.github.irgaly.kfswatch

/**
 * Error class
 */
data class KfsDirectoryWatcherError (
    val targetDirectory: String?,
    val message: String
)

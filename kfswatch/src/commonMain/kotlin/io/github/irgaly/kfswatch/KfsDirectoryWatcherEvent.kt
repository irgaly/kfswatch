package io.github.irgaly.kfswatch

/**
 * Watcher Event
 */
data class KfsDirectoryWatcherEvent(
    /**
     * Watching directory
     */
    val targetDirectory: String,
    /**
     * A file name or directory name of event
     */
    val path: String,
    /**
     * Event type
     */
    val event: KfsEvent
)

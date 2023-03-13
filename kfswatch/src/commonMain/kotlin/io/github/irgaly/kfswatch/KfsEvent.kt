package io.github.irgaly.kfswatch

import io.github.irgaly.kfswatch.internal.platform.FileWatcherEvent

/**
 * File System Event
 */
enum class KfsEvent {
    /**
     * Child file or directory has created.
     *
     * * this contains rename or move an watching directory
     */
    Create,

    /**
     * Child file or directory has deleted.
     *
     * * this contains rename or move an watching directory
     */
    Delete,

    /**
     * Child file's data has changed
     */
    Modify
    ;
    companion object {
        internal fun from(event: FileWatcherEvent): KfsEvent {
            return when(event) {
                FileWatcherEvent.Create -> Create
                FileWatcherEvent.Delete -> Delete
                FileWatcherEvent.Modify -> Modify
            }
        }
    }
}

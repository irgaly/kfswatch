package io.github.irgaly.kfswatch

import io.github.irgaly.kfswatch.internal.platform.FileWatcherRawEvent

/**
 * Watcher File System Raw Event
 */
sealed interface KfsDirectoryWatcherRawEvent {
    companion object {
        internal fun from(event: FileWatcherRawEvent): KfsDirectoryWatcherRawEvent {
            return when (event) {
                is FileWatcherRawEvent.AndroidFileObserverRawEvent -> {
                    AndroidFileObserverRawEvent(
                        targetDirectory = event.targetDirectory,
                        event = event.event,
                        path = event.path
                    )
                }

                is FileWatcherRawEvent.DarwinKernelQueuesRawEvent -> {
                    DarwinKernelQueuesRawEvent(
                        ident = event.ident,
                        fflags = event.fflags,
                        filter = event.filter,
                        flags = event.flags,
                        udata = event.udata
                    )
                }

                is FileWatcherRawEvent.NodejsFswatchRawEvent -> {
                    NodejsFswatchRawEvent(
                        targetDirectory = event.targetDirectory,
                        event = event.event,
                        filename = event.filename
                    )
                }

                is FileWatcherRawEvent.JvmWatchServiceRawEvent -> {
                    JvmWatchServiceRawEvent(
                        kind = event.kind,
                        count = event.count,
                        context = event.context,
                        contextAsPathString = event.contextAsPathString
                    )
                }

                is FileWatcherRawEvent.LinuxInotifyRawEvent -> {
                    LinuxInotifyRawEvent(
                        wd = event.wd,
                        name = event.name,
                        mask = event.mask,
                        len = event.len,
                        cookie = event.cookie
                    )
                }

                is FileWatcherRawEvent.WindowsReadDirectoryRawEvent -> {
                    WindowsReadDirectoryRawEvent(
                        targetDirectory = event.targetDirectory,
                        action = event.action,
                        filename = event.filename,
                        filenameLength = event.filenameLength,
                        nextEntryOffset = event.nextEntryOffset
                    )
                }
            }
        }
    }

    /**
     * Watching directory
     */
    val targetDirectory: String?

    /**
     * A file name or directory name of event
     */
    val path: String?

    /**
     * Android FileObserver Event
     *
     * https://developer.android.com/reference/kotlin/android/os/FileObserver
     */
    data class AndroidFileObserverRawEvent(
        override val targetDirectory: String,
        val event: Int,
        override val path: String?
    ) : KfsDirectoryWatcherRawEvent

    /**
     * iOS, macOS Kernel Queues Event
     *
     * https://developer.apple.com/library/archive/documentation/Darwin/Conceptual/FSEvents_ProgGuide/KernelQueues/KernelQueues.html
     */
    data class DarwinKernelQueuesRawEvent(
        val ident: ULong,
        val fflags: UInt,
        val filter: Short,
        val flags: UShort,
        val udata: ULong?
    ) : KfsDirectoryWatcherRawEvent {
        override val targetDirectory: String? = null
        override val path: String? = null
    }

    /**
     * Nodejs fs.watch Event
     *
     * https://nodejs.org/api/fs.html#fswatchfilename-options-listener
     */
    data class NodejsFswatchRawEvent(
        override val targetDirectory: String,
        val event: String,
        val filename: String?
    ) : KfsDirectoryWatcherRawEvent {
        override val path: String? = filename
    }

    /**
     * JVM WatchService Event
     *
     * https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/nio/file/WatchService.html
     */
    data class JvmWatchServiceRawEvent(
        val kind: String,
        val count: Int,
        val context: Any,
        val contextAsPathString: String?
    ) : KfsDirectoryWatcherRawEvent {
        override val targetDirectory: String? = null
        override val path: String? = contextAsPathString
    }

    /**
     * Linux inotify event
     *
     * https://manpages.ubuntu.com/manpages/bionic/en/man7/inotify.7.html
     */
    data class LinuxInotifyRawEvent(
        val wd: Int,
        val name: String,
        val mask: UInt,
        val len: UInt,
        val cookie: UInt
    ) : KfsDirectoryWatcherRawEvent {
        override val targetDirectory: String? = null
        override val path: String? = name
    }

    /**
     * Windows ReadDirectoryW Event
     *
     * https://learn.microsoft.com/en-us/windows/win32/api/winbase/nf-winbase-readdirectorychangesw
     */
    data class WindowsReadDirectoryRawEvent(
        override val targetDirectory: String,
        val action: UInt,
        val filename: String,
        val filenameLength: UInt,
        val nextEntryOffset: UInt
    ) : KfsDirectoryWatcherRawEvent {
        override val path: String? = filename
    }
}

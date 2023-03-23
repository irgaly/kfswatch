package io.github.irgaly.kfswatch

import io.github.irgaly.kfswatch.internal.platform.FileWatcher
import io.github.irgaly.kfswatch.internal.platform.FileWatcherEvent
import io.github.irgaly.kfswatch.internal.platform.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Kfswatch Directory Watcher
 *
 * This is not recursive watcher, only watch directory's child nodes.
 *
 * * Target directory's move, delete, or rename events are not watched.
 * * Events of child files or directories:
 *     * Create
 *     * Delete
 *     * Modify
 * * The implementation's are platform specific:
 *     * Android - FileObserver
 *     * iOS, macOS - kqueue (Kernel Queues)
 *     * JVM - WatchService
 *     * Windows - ReadDirectoryChangesW
 *     * Linux - inotify
 *     * Nodejs - fs.watch
 *     * Browser JS - Browser has no File System, No operation
 *
 * @param scope KfsDirectoryWatcher instance's lifecycle scope
 *              the instance well be automatically closed at scope's end.
 * @param dispatcher IO dispatcher, this is used on IO access, and KfsEvent dispatching.
 */
@Suppress("MemberVisibilityCanBePrivate", "unused")
class KfsDirectoryWatcher(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val logger: KfsLogger? = null
) {
    private val mutex = Mutex()
    private val onEventMutableSharedFlow = MutableSharedFlow<KfsDirectoryWatcherEvent>()
    private val onStartMutableSharedFlow = MutableSharedFlow<String>()
    private val onStopMutableSharedFlow = MutableSharedFlow<String>()
    private val onOverflowMutableSharedFlow = MutableSharedFlow<String?>()
    private val onErrorMutableSharedFlow = MutableSharedFlow<KfsDirectoryWatcherError>()
    private var watcher: FileWatcher = FileWatcher(
        onEvent = ::onEvent,
        onStart = ::onStart,
        onStop = ::onStop,
        onOverflow = ::onOverflow,
        onError = ::onError,
        logger = logger?.let { BridgeLogger(it) }
    )

    /**
     * List of directories that started watching.
     */
    var watchingDirectories: List<String> = mutableListOf()
        private set

    /**
     * File System Event flow
     */
    val onEventFlow: Flow<KfsDirectoryWatcherEvent> = onEventMutableSharedFlow.asSharedFlow()

    /**
     * Start watching event flow
     *
     * String: a directory started watching
     */
    val onStartFlow: Flow<String> = onStartMutableSharedFlow.asSharedFlow()

    /**
     * Stop watching event flow
     *
     * String: a directory stopped watching
     */
    val onStopFlow: Flow<String> = onStopMutableSharedFlow.asSharedFlow()

    /**
     * Events overflowed
     *
     * String?: a directory stopped watching or null
     *
     * * JVM: WatchService: Overflow event. targetDirectory is not null
     *     * WatchService has buffer, so Overflow has happen with many events (about 6000 events)
     * * Nodejs: fs.watch: No overflow event
     * * Android: FileObserver: No overflow event
     * * iOS, macOS: Kernel Queues: No overflow event
     * * Linux: inotify: Overflow event. targetDirectory is null
     *     * inotify has Overflow event, but it's rarely happen
     * * Windows: ReadDirectoryChangesW: Overflow event. targetDirectory is not null
     *     * ReadDirectoryChangesW has buffer, so Overflow has happen with many events (about 1000 events)
     */
    val onOverflowFlow: Flow<String?> = onOverflowMutableSharedFlow.asSharedFlow()

    /**
     * Error event flow
     */
    val onErrorFlow: Flow<KfsDirectoryWatcherError> = onErrorMutableSharedFlow.asSharedFlow()

    /**
     * Get this instance is closed or not
     */
    var closed: Boolean = false
        private set

    init {
        scope.coroutineContext.job.invokeOnCompletion {
            @OptIn(DelicateCoroutinesApi::class)
            GlobalScope.launch {
                close()
            }
        }
    }

    /**
     * Add target directories and start watching.
     *
     * Max count of watching target directory is 64.
     */
    suspend fun add(vararg targetDirectories: String) {
        withContext(dispatcher) {
            mutex.withLock {
                if (closed) {
                    error("This watcher instance is already closed")
                }
                watcher.start(targetDirectories.toList())
            }
        }
    }

    /**
     * Stop watching directories.
     */
    suspend fun remove(vararg targetDirectories: String) {
        withContext(dispatcher) {
            mutex.withLock {
                if (closed) {
                    error("This watcher instance is already closed")
                }
                watcher.stop(targetDirectories.toList())
            }
        }
    }

    /**
     * Stop watching all directories.
     */
    suspend fun removeAll() {
        withContext(dispatcher) {
            mutex.withLock {
                if (closed) {
                    error("This watcher instance is already closed")
                }
                watcher.stopAll()
            }
        }
    }

    /**
     * Pause watcher thread
     */
    suspend fun pause() {
        withContext(dispatcher) {
            mutex.withLock {
                if (closed) {
                    error("This watcher instance is already closed")
                }
                watcher.pause()
            }
        }
    }

    /**
     * Resume watcher thread
     */
    suspend fun resume() {
        withContext(dispatcher) {
            mutex.withLock {
                if (closed) {
                    error("This watcher instance is already closed")
                }
                watcher.resume()
            }
        }
    }

    /**
     * Stop all watcher and dispose all file system resources
     * Closed instance cannot reuse any more.
     *
     * KfsDirectoryWatcher will be automatically closed when [scope] is ended.
     */
    suspend fun close() {
        withContext(dispatcher) {
            mutex.withLock {
                if (!closed) {
                    closed = true
                    watcher.close()
                }
            }
        }
    }

    private fun onEvent(targetDirectory: String, path: String, event: FileWatcherEvent) {
        scope.launch(dispatcher) {
            logger?.debug("onEvent: target=$targetDirectory, path=$path, event=$event")
            onEventMutableSharedFlow.emit(
                KfsDirectoryWatcherEvent(
                    targetDirectory,
                    path,
                    KfsEvent.from(event)
                )
            )
        }
    }

    private fun onStart(targetDirectory: String) {
        scope.launch(dispatcher) {
            logger?.debug("onStart: target=$targetDirectory")
            mutex.withLock {
                watchingDirectories = watchingDirectories + targetDirectory
            }
            onStartMutableSharedFlow.emit(targetDirectory)
        }
    }

    private fun onStop(targetDirectory: String) {
        scope.launch(dispatcher) {
            logger?.debug("onStop: target=$targetDirectory")
            mutex.withLock {
                watchingDirectories = watchingDirectories - targetDirectory
            }
            onStopMutableSharedFlow.emit(targetDirectory)
        }
    }

    private fun onOverflow(targetDirectory: String?) {
        scope.launch(dispatcher) {
            logger?.debug("onOverflow: target=$targetDirectory")
            onOverflowMutableSharedFlow.emit(targetDirectory)
        }
    }

    private fun onError(targetDirectory: String?, message: String) {
        scope.launch(dispatcher) {
            logger?.error("onError: target=$targetDirectory, message=$message")
            onErrorMutableSharedFlow.emit(
                KfsDirectoryWatcherError(
                    targetDirectory,
                    message
                )
            )
        }
    }

    private inner class BridgeLogger(
        private val logger: KfsLogger
    ): Logger {
        override fun debug(message: () -> String) {
            logger.debug(message())
        }

        override fun error(message: () -> String) {
            logger.error(message())
        }
    }
}

package io.github.irgaly.kfswatch.internal.platform

import android.os.Build
import android.os.FileObserver
import android.os.FileObserver.CREATE
import android.os.FileObserver.DELETE
import android.os.FileObserver.MODIFY
import android.os.FileObserver.MOVED_FROM
import android.os.FileObserver.MOVED_TO
import java.io.File
import java.util.concurrent.Semaphore
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

/**
 * Android FileObserver
 *
 * https://developer.android.com/reference/kotlin/android/os/FileObserver
 */
internal actual class FileWatcher actual constructor(
    private val onEvent: (targetDirectory: String, path: String, event: FileWatcherEvent) -> Unit,
    private val onStart: (targetDirectory: String) -> Unit,
    private val onStop: (targetDirectory: String) -> Unit,
    private val onOverflow: (targetDirectory: String?) -> Unit,
    private val onError: (targetDirectory: String?, message: String) -> Unit,
    private val logger: Logger?
) {
    private val lock = ReentrantLock()
    private val threadLock = Semaphore(1)
    private val observers: MutableMap<String, FileObserver> = mutableMapOf()

    actual fun start(targetDirectories: List<String>) {
        lock.withLock {
            for(targetDirectory in targetDirectories.subtract(observers.keys)) {
                if (FileWatcherMaxTargets <= observers.size) {
                    onError(targetDirectory, "too many targets: max = $FileWatcherMaxTargets, cannot start watching $targetDirectory")
                    continue
                }
                val targetFile = File(targetDirectory)
                if (!targetFile.exists()) {
                    onError(targetDirectory, "directory not exists: $targetDirectory")
                    continue
                }
                if (!targetFile.isDirectory) {
                    onError(targetDirectory, "target is not directory: $targetDirectory")
                    continue
                }
                val mask =
                    CREATE or // 子の CREATE
                            DELETE or // 子の DELETE
                            MOVED_FROM or // 子の移動
                            MOVED_TO or // 子の移動
                            MODIFY // 子の変更
                fun handleEvent(event: Int, path: String?) {
                    // threadLock が unlock されるまで通知を止める
                    threadLock.acquire()
                    threadLock.release()
                    logger?.debug {
                        "FileObserver.onEvent: event = ${event.toString(16)}, path = $path"
                    }
                    @Suppress("LocalVariableName")
                    val IN_IGNORED = 0x00008000 // inotify 監視終了イベント
                    when {
                        ((event and CREATE) == CREATE) -> {
                            onEvent(targetDirectory, checkNotNull(path), FileWatcherEvent.Create)
                        }

                        ((event and DELETE) == DELETE) -> {
                            onEvent(targetDirectory, checkNotNull(path), FileWatcherEvent.Delete)
                        }

                        ((event and MOVED_FROM) == MOVED_FROM) -> {
                            onEvent(targetDirectory, checkNotNull(path), FileWatcherEvent.Delete)
                        }

                        ((event and MOVED_TO) == MOVED_TO) -> {
                            onEvent(targetDirectory, checkNotNull(path), FileWatcherEvent.Create)
                        }

                        ((event and MODIFY) == MODIFY) -> {
                            onEvent(targetDirectory, checkNotNull(path), FileWatcherEvent.Modify)
                        }

                        ((event and IN_IGNORED) == IN_IGNORED) -> {
                            stop(listOf(targetDirectory))
                            onStop(targetDirectory)
                        }
                    }
                }
                val observer = if (Build.VERSION_CODES.Q <= Build.VERSION.SDK_INT) {
                    object : FileObserver(targetFile, mask) {
                        override fun onEvent(event: Int, path: String?) {
                            handleEvent(event, path)
                        }
                    }
                } else {
                    @Suppress("DEPRECATION")
                    object : FileObserver(targetDirectory, mask) {
                        override fun onEvent(event: Int, path: String?) {
                            handleEvent(event, path)
                        }
                    }
                }
                observer.startWatching()
                observers[targetDirectory] = observer
                onStart(targetDirectory)
            }
        }
    }

    actual fun stop(targetDirectories: List<String>) {
        lock.withLock {
            targetDirectories.forEach { targetDirectory ->
                val observer = observers[targetDirectory]
                if (observer != null) {
                    observer.stopWatching()
                    observers.remove(targetDirectory)
                }
            }
        }
    }

    actual fun stopAll() {
        lock.withLock {
            observers.forEach {
                it.value.stopWatching()
            }
            observers.clear()
        }
    }

    actual fun pause() {
        threadLock.acquire()
    }

    actual fun resume() {
        threadLock.release()
    }

    actual fun close() {
        thread(
            name = "Kfswatch_FileWatcher_close",
            priority = Thread.NORM_PRIORITY
        ) {
            stopAll()
        }
    }
}

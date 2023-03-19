package io.github.irgaly.kfswatch.internal.platform

import com.sun.nio.file.SensitivityWatchEventModifier
import java.io.File
import java.io.IOException
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock
import kotlin.io.path.pathString

/**
 * JVM WatchService
 *
 * https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/nio/file/WatchService.html
 */
internal actual class FileWatcher actual constructor(
    private val onEvent: (targetDirectory: String, path: String, event: FileWatcherEvent) -> Unit,
    private val onStart: (targetDirectory: String) -> Unit,
    private val onStop: (targetDirectory: String) -> Unit,
    private val onError: (targetDirectory: String?, message: String) -> Unit,
    private val logger: Logger?
) {
    private val lock = ReentrantLock()
    private var watchService: WatchService? = null
    private val keys: MutableMap<PlatformPath, WatchKey> = mutableMapOf()

    actual fun start(targetDirectories: List<String>) {
        lock.withLock {
            var watchService: WatchService? = this.watchService
            for (targetDirectoryPath in targetDirectories.map { PlatformPath(it) }
                .subtract(keys.keys)) {
                val targetDirectory = targetDirectoryPath.originalPath
                if (FileWatcherMaxTargets <= keys.size) {
                    onError(
                        targetDirectory,
                        "too many targets: max = $FileWatcherMaxTargets, cannot start watching $targetDirectory"
                    )
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
                try {
                    if (watchService == null) {
                        watchService = FileSystems.getDefault().newWatchService()
                    }
                    val key = targetFile.toPath().register(
                        watchService,
                        arrayOf(
                            StandardWatchEventKinds.ENTRY_CREATE,
                            StandardWatchEventKinds.ENTRY_DELETE,
                            StandardWatchEventKinds.ENTRY_MODIFY
                        ),
                        // JVM on macOS のポーリング実装で、2秒程度のポーリングとする独自オプション
                        SensitivityWatchEventModifier.HIGH
                    )
                    keys[targetDirectoryPath] = key
                    onStart(targetDirectory)
                } catch (error: IOException) {
                    // FileSystem.newWatchService(), Path.register()
                    onError(targetDirectory, "FileWatcher.start() failed: $error")
                }
            }
            if (this.watchService == null && watchService != null) {
                this.watchService = watchService
                // WatchService 監視スレッドの起動
                thread(
                    name = "Kfswatch_FileWatcher",
                    priority = Thread.MIN_PRIORITY
                ) {
                    logger?.debug { "FileWatcher thread start" }
                    try {
                        while (true) {
                            val finishing = lock.withLock {
                                keys.isEmpty()
                            }
                            if (finishing) {
                                break
                            }
                            // イベント発生までスレッド停止
                            val key = watchService.take()
                            val targetDirectory = (key.watchable() as Path).pathString
                            key.pollEvents().forEach { event ->
                                logger?.debug {
                                    "WatchService: kind = ${event.kind()}, context = ${event.context()}, count = ${event.count()}"
                                }
                                val path = (event.context() as Path).pathString
                                when (event.kind()) {
                                    StandardWatchEventKinds.ENTRY_CREATE -> {
                                        onEvent(targetDirectory, path, FileWatcherEvent.Create)
                                    }

                                    StandardWatchEventKinds.ENTRY_DELETE -> {
                                        onEvent(targetDirectory, path, FileWatcherEvent.Delete)
                                    }

                                    StandardWatchEventKinds.ENTRY_MODIFY -> {
                                        onEvent(targetDirectory, path, FileWatcherEvent.Modify)
                                    }

                                    StandardWatchEventKinds.OVERFLOW -> {
                                        onError(
                                            targetDirectory,
                                            "Events overflowed: $targetDirectory"
                                        )
                                    }
                                }
                            }
                            val valid = key.reset()
                            if (!valid) {
                                logger?.debug {
                                    "WatchService: key is invalid, stop watching: ${(key.watchable() as Path).pathString}"
                                }
                                stop(listOf((key.watchable() as Path).pathString))
                            }
                        }
                    } catch (_: ClosedWatchServiceException) {
                        // WatchService.take(): WatchService.close() でキャンセルされた
                    } catch (_: InterruptedException) {
                        // WatchService.take(): 割り込みなどによる監視停止
                    }
                    logger?.debug { "FileWatcher thread finishing" }
                    lock.withLock {
                        if (this.watchService == watchService) {
                            // 例外による監視終了処理
                            this.watchService?.close()
                            this.watchService = null
                        }
                    }
                }
            }
        }
    }

    actual fun stop(targetDirectories: List<String>) {
        lock.withLock {
            targetDirectories.forEach { targetDirectory ->
                val path = PlatformPath(targetDirectory)
                val originalEntry = keys.entries.firstOrNull { it.key == path }
                if (originalEntry != null) {
                    originalEntry.value.cancel()
                    onStop(originalEntry.key.originalPath)
                    keys.remove(path)
                }
            }
            if (keys.isEmpty()) {
                watchService?.close()
                watchService = null
            }
        }
    }

    actual fun stopAll() {
        lock.withLock {
            watchService?.close()
            watchService = null
            keys.forEach {
                onStop(it.key.originalPath)
            }
            keys.clear()
        }
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

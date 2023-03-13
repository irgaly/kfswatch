package io.github.irgaly.kfswatch.internal.platform

private val fs: dynamic get() = js("require('fs')")

external interface FSWatcher {
    fun close()
}

/**
 * Nodejs fs.watch
 *
 * https://nodejs.org/api/fs.html#fswatchfilename-options-listener
 */
internal actual class FileWatcher actual constructor(
    private val onEvent: (targetDirectory: String, path: String, event: FileWatcherEvent) -> Unit,
    private val onStart: (targetDirectory: String) -> Unit,
    private val onStop: (targetDirectory: String) -> Unit,
    private val onError: (targetDirectory: String?, message: String) -> Unit,
    private val logger: Logger?
) {
    private val watchers: MutableMap<String, FSWatcher> = mutableMapOf()

    actual fun start(targetDirectories: List<String>) {
        if(isNodejs()) {
            for(targetDirectory in targetDirectories.subtract(watchers.keys)) {
                if (FileWatcherMaxTargets <= watchers.size) {
                    onError(targetDirectory, "too many targets: max = $FileWatcherMaxTargets, cannot start watching $targetDirectory")
                    continue
                }
                if (!fs.existsSync(targetDirectory).unsafeCast<Boolean>()) {
                    onError(targetDirectory, "directory not exists: $targetDirectory")
                    continue
                }
                val stats = fs.statSync(targetDirectory)
                if (!stats.isDirectory().unsafeCast<Boolean>()) {
                    onError(targetDirectory, "target is not directory: $targetDirectory")
                    continue
                }
                val children = mutableSetOf<String>()
                val watcher = fs.watch(targetDirectory) { event: String, filename: String? ->
                    when (event) {
                        "rename" -> {
                            // 子要素の作成、削除、名前変更
                            val path = checkNotNull(filename)
                            val beforeExists = children.contains(path)
                            var afterExists = fs.existsSync("$targetDirectory/$path").unsafeCast<Boolean>()
                            if (beforeExists) {
                                if (afterExists) {
                                    // 対象が削除され、同名の要素が作成された (上書き move など)
                                    // Delete イベントだけ通知し、Create は次のイベントで通知する
                                    onEvent(targetDirectory, path, FileWatcherEvent.Delete)
                                    afterExists = false
                                } else {
                                    // 対象がディレクトリから削除された
                                    onEvent(targetDirectory, path, FileWatcherEvent.Delete)
                                }
                            } else {
                                if (afterExists) {
                                    // 対象が作成された
                                    onEvent(targetDirectory, path, FileWatcherEvent.Create)
                                } else {
                                    // 対象が作成されたがすぐに削除された
                                    // Create イベントだけ通知し、Delete は次のイベントで通知する
                                    onEvent(targetDirectory, path, FileWatcherEvent.Create)
                                    afterExists = true
                                }
                            }
                            if (afterExists) {
                                children.add(path)
                            } else {
                                children.remove(path)
                            }
                        }
                        "change" -> {
                            // 子要素の内容変更
                            onEvent(targetDirectory, checkNotNull(filename), FileWatcherEvent.Modify)
                        }
                        else -> {
                            // rename, change 以外のイベントは存在しない
                        }
                    }
                }
                children.addAll(
                    fs.readdirSync(targetDirectory).unsafeCast<List<String>>()
                )
                watchers[targetDirectory] = watcher.unsafeCast<FSWatcher>()
                onStart(targetDirectory)
            }
        }
    }

    actual fun stop(targetDirectories: List<String>) {
        targetDirectories.forEach { targetDirectory ->
            val watcher = watchers[targetDirectory]
            if (watcher != null) {
                watcher.close()
                onStop(targetDirectory)
                watchers.remove(targetDirectory)
            }
        }
    }

    actual fun stopAll() {
        watchers.forEach {
            it.value.close()
            onStop(it.key)
        }
        watchers.clear()
    }

    actual fun close() {
        stopAll()
    }
}

package io.github.irgaly.kfswatch.internal.platform

private fun fs(): FsSync = js("require('fs')")
private fun path(): Path = js("require('path')")
private fun json(): JsJson = js("JSON")

private external interface JsJson : JsAny {
    fun stringify(value: JsAny): String
}

private external interface FsSync : JsAny {
    fun existsSync(path: String): Boolean
    fun statSync(path: String): Stats
    fun readdirSync(path: String): JsArray<JsString>
    fun watch(filename: String, listener: (eventType: String, filename: String?) -> Unit): FsWatcher
}

private fun jsArrayToArray(array: JsArray<JsString>): Array<String> {
    return (0..array.length).map { index ->
        array[index].toString()
    }.toTypedArray()
}

private external interface Stats : JsAny {
    fun isDirectory(): Boolean
}

private external interface FsWatcher : JsAny {
    fun on(eventName: String, listener: (event: JsAny) -> Unit)
    fun close()
}

private external interface Path : JsAny {
    fun resolve(path: String): String
    fun dirname(path: String): String
    fun basename(path: String): String
}

private external interface Events : JsAny {
    fun once(eventName: String, listener: () -> Unit): JsAny
    fun emit(eventName: String)
}

private fun requireEvents(): Events = js("new (require('events'))()")

/**
 * Nodejs fs.watch
 *
 * https://nodejs.org/api/fs.html#fswatchfilename-options-listener
 */
internal actual class FileWatcher actual constructor(
    private val onEvent: (targetDirectory: String, path: String, event: FileWatcherEvent) -> Unit,
    private val onStart: (targetDirectory: String) -> Unit,
    private val onStop: (targetDirectory: String) -> Unit,
    private val onOverflow: (targetDirectory: String?) -> Unit,
    private val onError: (targetDirectory: String?, message: String) -> Unit,
    private val onRawEvent: ((event: FileWatcherRawEvent) -> Unit)?,
    private val logger: Logger?
) {
    private val watchers: MutableMap<PlatformPath, Watcher> = mutableMapOf()
    private var paused = false
    private val resumedEvent: Events = requireEvents()

    private data class Watcher(
        val parentWatcher: FsWatcher?,
        val watcher: FsWatcher
    )

    actual fun start(targetDirectories: List<String>) {
        if (isNodejs()) {
            val fs = fs()
            val path = path()
            val json = json()
            for (targetDirectoryPath in targetDirectories.map { PlatformPath(it) }
                .subtract(watchers.keys)) {
                val targetDirectory = targetDirectoryPath.originalPath
                if (FileWatcherMaxTargets <= watchers.size) {
                    onError(
                        targetDirectory,
                        "too many targets: max = $FileWatcherMaxTargets, cannot start watching $targetDirectory"
                    )
                    continue
                }
                if (!fs.existsSync(targetDirectory)) {
                    onError(targetDirectory, "directory not exists: $targetDirectory")
                    continue
                }
                val stats = fs.statSync(targetDirectory)
                if (!stats.isDirectory()) {
                    onError(targetDirectory, "target is not directory: $targetDirectory")
                    continue
                }
                val absolutePath = path.resolve(targetDirectory)
                val parent = path.dirname(absolutePath)
                val parentWatcher = if (parent != absolutePath) {
                    // 監視対象が / でなければ監視対象の移動を検出するために親ディレクトリを監視する
                    val targetName = path.basename(absolutePath)
                    fs.watch(parent) { event: String, filename: String? ->
                        logger?.debug {
                            "fs.watch parent: event = $event, filename = $filename, target = $parent"
                        }
                        if (event == "rename" && filename == targetName) {
                            if (!fs.existsSync(targetDirectory)) {
                                // 監視対象が削除された
                                // 監視停止
                                stop(listOf(targetDirectory))
                            }
                        }
                    }
                } else null
                val children = mutableSetOf<String>()
                val watcher = fs.watch(targetDirectory) { event: String, filename: String? ->
                    val handleEvent = {
                        logger?.debug {
                            "fs.watch: event = $event, filename = $filename, target = $targetDirectory"
                        }
                        onRawEvent?.invoke(
                            FileWatcherRawEvent.NodejsFswatchRawEvent(
                                targetDirectory = targetDirectory,
                                eventType = event,
                                filename = filename
                            )
                        )
                        val targetExists = fs.existsSync(targetDirectory)
                        if (!targetExists) {
                            // * 監視対象が移動または削除された
                            // * 監視対象の親ディレクトリが移動または削除された
                            stop(listOf(targetDirectory))
                        } else {
                            when (event) {
                                "rename" -> {
                                    // 子要素の作成、削除、名前変更、内容変更
                                    val path = checkNotNull(filename)
                                    val beforeExists = children.contains(path)
                                    val afterExists =
                                        fs.existsSync("$targetDirectory/$path")
                                    if (beforeExists) {
                                        if (afterExists) {
                                            // * 対象が削除され、同名の要素が作成された (上書き move など)
                                            // * ファイル内容が置き換えられた (内容削除を伴う上書き書き込みなど)
                                            onEvent(targetDirectory, path, FileWatcherEvent.Modify)
                                        } else {
                                            // 対象がディレクトリから削除された
                                            onEvent(targetDirectory, path, FileWatcherEvent.Delete)
                                        }
                                    } else {
                                        if (afterExists) {
                                            // 対象が作成された
                                            onEvent(targetDirectory, path, FileWatcherEvent.Create)
                                        } else {
                                            // 以下のどちらの状況か判定ができないため Delete イベントとする
                                            // どちらの場合でも Delete イベントが2回流れることになる
                                            // * 対象が作成されたがすぐに削除された
                                            // * 対象が削除されたとき、rename イベントが2回発生した
                                            onEvent(targetDirectory, path, FileWatcherEvent.Delete)
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
                                    onEvent(
                                        targetDirectory,
                                        checkNotNull(filename),
                                        FileWatcherEvent.Modify
                                    )
                                }

                                else -> {
                                    // rename, change 以外のイベントは存在しない
                                }
                            }
                        }
                    }
                    if (paused) {
                        // paused 状態であれば、resume されるまでイベント通知を止める
                        resumedEvent.once("resumed") {
                            handleEvent()
                        }
                    } else {
                        handleEvent()
                    }
                }
                watcher.on("error") { error ->
                    logger?.debug {
                        "fs.watch on error: error = ${json.stringify(error)}"
                    }
                    if (!fs.existsSync(targetDirectory)) {
                        // Windows で監視対象ディレクトリが削除された
                        stop(listOf(targetDirectory))
                    }
                }
                children.addAll(
                    jsArrayToArray(fs.readdirSync(targetDirectory))
                )
                watchers[targetDirectoryPath] = Watcher(
                    parentWatcher,
                    watcher
                )
                onStart(targetDirectory)
            }
        }
    }

    actual fun stop(targetDirectories: List<String>) {
        targetDirectories.forEach { targetDirectory ->
            val path = PlatformPath(targetDirectory)
            val originalEntry = watchers.entries.firstOrNull { it.key == path }
            if (originalEntry != null) {
                originalEntry.value.parentWatcher?.close()
                originalEntry.value.watcher.close()
                onStop(originalEntry.key.originalPath)
                watchers.remove(path)
            }
        }
    }

    actual fun stopAll() {
        watchers.forEach {
            it.value.parentWatcher?.close()
            it.value.watcher.close()
            onStop(it.key.originalPath)
        }
        watchers.clear()
    }

    actual fun pause() {
        paused = true
    }

    actual fun resume() {
        paused = false
        resumedEvent.emit("resumed")
    }

    actual fun close() {
        stopAll()
    }
}

package io.github.irgaly.kfswatch.internal.platform

/**
 * Simple Directory Watcher
 */
internal expect class FileWatcher(
    onEvent: (targetDirectory: String, path: String, event: FileWatcherEvent) -> Unit,
    onStart: (targetDirectory: String) -> Unit,
    onStop: (targetDirectory: String) -> Unit,
    onOverflow: (targetDirectory: String?) -> Unit,
    onError: (targetDirectory: String?, message: String) -> Unit,
    logger: Logger? = null
) {
    fun start(targetDirectories: List<String>)
    fun stop(targetDirectories: List<String>)
    fun stopAll()
    fun pause()
    fun resume()

    /**
     * FileWatcher の無効化
     * 保持リソースの解放
     * FileWatcher を使い終わったら必ず実行する必要がある
     * close 後は FileWatcher は使用できない
     * close() は非ブロッキング処理とする
     */
    fun close()
}

/**
 * 同時に監視できる最大数は 63 (Windows の制限に合わせる)
 *
 * Windows WaitForMultipleObjects() MAXIMUM_WAIT_OBJECTS = 64
 * 64 - スレッド制御 Event 1 = 63
 */
const val FileWatcherMaxTargets = 63

internal enum class FileWatcherEvent {
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
}

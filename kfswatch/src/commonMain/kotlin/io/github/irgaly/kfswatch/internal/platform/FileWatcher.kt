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
    onRawEvent: ((event: FileWatcherRawEvent) -> Unit)?,
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

internal sealed interface FileWatcherRawEvent {
    data class AndroidFileObserverRawEvent(
        val targetDirectory: String,
        val event: Int,
        val path: String?
    ) : FileWatcherRawEvent

    data class DarwinKernelQueuesRawEvent(
        val ident: ULong,
        val fflags: UInt,
        val filter: Short,
        val flags: UShort,
        val udata: ULong?
    ) : FileWatcherRawEvent

    data class NodejsFswatchRawEvent(
        val targetDirectory: String,
        val event: String,
        val filename: String?
    ) : FileWatcherRawEvent

    data class JvmWatchServiceRawEvent(
        val kind: String,
        val count: Int,
        val context: Any,
        val contextAsPathString: String?
    ) : FileWatcherRawEvent

    data class LinuxInotifyRawEvent(
        val wd: Int,
        val name: String,
        val mask: UInt,
        val len: UInt,
        val cookie: UInt
    ) : FileWatcherRawEvent

    data class WindowsReadDirectoryRawEvent(
        val targetDirectory: String,
        val action: UInt,
        val filename: String,
        val filenameLength: UInt,
        val nextEntryOffset: UInt
    ) : FileWatcherRawEvent
}

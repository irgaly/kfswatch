package io.github.irgaly.kfswatch.internal.platform

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.free
import kotlinx.cinterop.interpretCPointer
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import platform.posix.memcpy
import platform.windows.CRITICAL_SECTION
import platform.windows.CancelIo
import platform.windows.CloseHandle
import platform.windows.CreateEventW
import platform.windows.CreateFileW
import platform.windows.DWORDVar
import platform.windows.DeleteCriticalSection
import platform.windows.ERROR_OPERATION_ABORTED
import platform.windows.EnterCriticalSection
import platform.windows.FALSE
import platform.windows.FILE_ACTION_ADDED
import platform.windows.FILE_ACTION_MODIFIED
import platform.windows.FILE_ACTION_REMOVED
import platform.windows.FILE_ACTION_RENAMED_NEW_NAME
import platform.windows.FILE_ACTION_RENAMED_OLD_NAME
import platform.windows.FILE_ATTRIBUTE_DIRECTORY
import platform.windows.FILE_FLAG_BACKUP_SEMANTICS
import platform.windows.FILE_FLAG_OVERLAPPED
import platform.windows.FILE_LIST_DIRECTORY
import platform.windows.FILE_NOTIFY_CHANGE_DIR_NAME
import platform.windows.FILE_NOTIFY_CHANGE_FILE_NAME
import platform.windows.FILE_NOTIFY_CHANGE_LAST_WRITE
import platform.windows.FILE_NOTIFY_INFORMATION
import platform.windows.FILE_SHARE_DELETE
import platform.windows.FILE_SHARE_READ
import platform.windows.FILE_SHARE_WRITE
import platform.windows.GetFileAttributesW
import platform.windows.GetLastError
import platform.windows.GetOverlappedResult
import platform.windows.HANDLE
import platform.windows.INFINITE
import platform.windows.INVALID_FILE_ATTRIBUTES
import platform.windows.INVALID_HANDLE_VALUE
import platform.windows.InitializeCriticalSection
import platform.windows.LeaveCriticalSection
import platform.windows.MAXIMUM_WAIT_OBJECTS
import platform.windows.OPEN_EXISTING
import platform.windows.OVERLAPPED
import platform.windows.ReadDirectoryChangesW
import platform.windows.ResetEvent
import platform.windows.SetEvent
import platform.windows.TRUE
import platform.windows.WAIT_OBJECT_0
import platform.windows.WCHARVar
import platform.windows.WaitForMultipleObjects

/**
 * ReadDirectoryChangesW
 *
 * https://learn.microsoft.com/ja-jp/windows/win32/api/winbase/nf-winbase-readdirectorychangesw
 */
internal actual class FileWatcher actual constructor(
    private val onEvent: (targetDirectory: String, path: String, event: FileWatcherEvent) -> Unit,
    private val onStart: (targetDirectory: String) -> Unit,
    private val onStop: (targetDirectory: String) -> Unit,
    private val onError: (targetDirectory: String?, message: String) -> Unit,
    private val logger: Logger?
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val dispatcher = newSingleThreadContext("FileWatcher")
    private val criticalSectionPointer: CPointer<CRITICAL_SECTION> by lazy {
        val value = nativeHeap.alloc<CRITICAL_SECTION>()
        InitializeCriticalSection(value.ptr)
        value.ptr
    }
    private var threadResource: ThreadResource? = null
    private val targetStatuses: LinkedHashMap<PlatformPath, WatchStatus> = linkedMapOf()

    private data class ThreadResource(
        val threadResetHandle: HANDLE,
        var disposing: Boolean
    )

    private data class WatchStatus(
        var data: ReadDirectoryData?,
        var state: WatchState
    )

    private enum class WatchState {
        Watching,
        Adding,
        Stopping
    }

    private data class ReadDirectoryData(
        val directoryHandle: HANDLE,
        val eventHandle: HANDLE,
        val overlapped: OVERLAPPED,
        val buffer: COpaquePointer
    )

    actual fun start(targetDirectories: List<String>) {
        withLock {
            var resource = threadResource
            for (targetDirectoryPath in targetDirectories.map { PlatformPath(it) }.subtract(
                targetStatuses.filter {
                    (it.value.state == WatchState.Watching || it.value.state == WatchState.Adding)
                }.map { it.key }.toSet()
            )) {
                val targetDirectory = targetDirectoryPath.originalPath
                if (FileWatcherMaxTargets <= targetStatuses.count {
                        (it.value.state == WatchState.Watching || it.value.state == WatchState.Adding)
                    }) {
                    onError(
                        targetDirectory,
                        "too many targets: max = $FileWatcherMaxTargets, cannot start watching $targetDirectory"
                    )
                    continue
                }
                val fileAttributes = GetFileAttributesW(targetDirectory)
                if (fileAttributes == INVALID_FILE_ATTRIBUTES) {
                    onError(targetDirectory, "cannot open target: $targetDirectory")
                    continue
                }
                val isDirectory = ((fileAttributes.toInt() and FILE_ATTRIBUTE_DIRECTORY) != 0)
                if (!isDirectory) {
                    onError(targetDirectory, "target is not directory: $targetDirectory")
                    continue
                }
                if (resource == null) {
                    // 監視スレッドの状態リセットイベント
                    val threadResetHandle = CreateEventW(
                        lpEventAttributes = null,
                        bManualReset = TRUE,
                        bInitialState = FALSE,
                        lpName = null
                    )
                    if (threadResetHandle == null) {
                        val errorCode = GetLastError()
                        onError(
                            targetDirectory,
                            "threadResetHandle CreateEventW failed: error=$errorCode, $targetDirectory"
                        )
                        continue
                    }
                    resource = ThreadResource(threadResetHandle, false)
                }
                targetStatuses[targetDirectoryPath] = WatchStatus(null, WatchState.Adding)
            }
            if (resource != null) {
                if (threadResource != null) {
                    // スレッドリセットイベント送信
                    logger?.debug { "send thread reset for adding" }
                    SetEvent(checkNotNull(threadResource).threadResetHandle)
                } else {
                    threadResource = resource
                    // 監視スレッドの起動
                    // スレッドの状態を厳密に管理しているため GlobalScope での起動を許容する
                    @OptIn(DelicateCoroutinesApi::class)
                    GlobalScope.launch(dispatcher) {
                        watchingThread()
                    }
                }
            }
        }
    }

    actual fun stop(targetDirectories: List<String>) {
        withLock {
            var changed = false
            targetDirectories.forEach { targetDirectory ->
                val path = PlatformPath(targetDirectory)
                val status = targetStatuses[path]
                if (status != null) {
                    when (status.state) {
                        WatchState.Watching -> {
                            status.state = WatchState.Stopping
                            changed = true
                        }

                        WatchState.Adding -> {
                            targetStatuses.remove(path)
                        }

                        WatchState.Stopping -> {}
                    }
                }
            }
            if (changed) {
                // スレッドのリセット指示
                logger?.debug { "send thread reset" }
                SetEvent(checkNotNull(threadResource).threadResetHandle)
            }
        }
    }

    actual fun stopAll() {
        withLock {
            var changed = false
            targetStatuses.toList().forEach {
                when (it.second.state) {
                    WatchState.Watching -> {
                        it.second.state = WatchState.Stopping
                        changed = true
                    }

                    WatchState.Adding -> {
                        targetStatuses.remove(it.first)
                    }

                    WatchState.Stopping -> {}
                }
            }
            if (changed) {
                // スレッドのリセット指示
                logger?.debug { "send thread reset" }
                SetEvent(checkNotNull(threadResource).threadResetHandle)
            }
        }
    }

    private fun watchingThread() {
        memScoped {
            logger?.debug { "watchingThread() start" }
            // DWORD = 32 bits = 4 bytes
            // DWORD * (1024 * 2 length) = 4 * 1024 * 2 = 8 KB
            val bufferLength = 1024 * 2
            val bufferSize = (sizeOf<DWORDVar>() * bufferLength).toUInt()
            val bytesReturned = alloc<DWORDVar>()
            val resetTargets = mutableMapOf<PlatformPath, ReadDirectoryData>()
            var threadResetHandle: HANDLE? = null
            var finishing = false
            var disposing = false
            while (true) {
                val watchTargets: LinkedHashMap<PlatformPath, ReadDirectoryData> = linkedMapOf()
                withLock {
                    val resource = checkNotNull(threadResource)
                    threadResetHandle = resource.threadResetHandle
                    disposing = resource.disposing
                    fun stopWatching(targetPath: PlatformPath) {
                        logger?.debug { "stopWatching CloseHandle: ${targetPath.originalPath}" }
                        val data = checkNotNull(targetStatuses[targetPath]?.data)
                        CancelIo(data.directoryHandle)
                        CloseHandle(data.directoryHandle)
                        CloseHandle(data.eventHandle)
                        nativeHeap.free(data.overlapped)
                        nativeHeap.free(data.buffer)
                        targetStatuses.remove(targetPath)
                        resetTargets.remove(targetPath)
                        onStop(targetPath.originalPath)
                    }
                    for (entry in targetStatuses.toList()) {
                        if (entry.second.state != WatchState.Watching) {
                            logger?.debug { "status: $entry" }
                        }
                        val targetPath = entry.first
                        if (finishing || disposing) {
                            // 終了処理
                            when (entry.second.state) {
                                WatchState.Adding -> {
                                    targetStatuses.remove(targetPath)
                                }

                                WatchState.Watching,
                                WatchState.Stopping -> {
                                    // 登録解除
                                    stopWatching(targetPath)
                                }
                            }
                        } else {
                            when (entry.second.state) {
                                WatchState.Watching -> {}
                                WatchState.Adding -> {
                                    val buffer = nativeHeap.allocArray<DWORDVar>(bufferLength)
                                    val overlapped = nativeHeap.alloc<OVERLAPPED>()
                                    val eventHandle = CreateEventW(
                                        lpEventAttributes = null,
                                        bManualReset = TRUE,
                                        bInitialState = FALSE,
                                        lpName = null
                                    ).also {
                                        overlapped.hEvent = it
                                    }
                                    if (eventHandle == null) {
                                        val errorCode = GetLastError()
                                        onError(
                                            targetPath.originalPath,
                                            "CreateEventW failed: error=$errorCode, ${targetPath.originalPath}"
                                        )
                                        nativeHeap.free(overlapped)
                                        nativeHeap.free(buffer)
                                        targetStatuses.remove(targetPath)
                                        continue
                                    }
                                    val handle = checkNotNull(
                                        CreateFileW(
                                            lpFileName = targetPath.originalPath,
                                            dwDesiredAccess = FILE_LIST_DIRECTORY,
                                            dwShareMode = (FILE_SHARE_READ or FILE_SHARE_WRITE or FILE_SHARE_DELETE).toUInt(),
                                            lpSecurityAttributes = null,
                                            dwCreationDisposition = OPEN_EXISTING,
                                            dwFlagsAndAttributes = (FILE_FLAG_BACKUP_SEMANTICS or FILE_FLAG_OVERLAPPED).toUInt(),
                                            hTemplateFile = null
                                        )
                                    )
                                    if (handle == INVALID_HANDLE_VALUE) {
                                        val errorCode = GetLastError()
                                        onError(
                                            targetPath.originalPath,
                                            "cannot open target: error=$errorCode, ${targetPath.originalPath}"
                                        )
                                        CloseHandle(eventHandle)
                                        nativeHeap.free(overlapped)
                                        nativeHeap.free(buffer)
                                        targetStatuses.remove(targetPath)
                                        continue
                                    }
                                    val data = ReadDirectoryData(
                                        handle,
                                        eventHandle,
                                        overlapped,
                                        buffer
                                    )
                                    entry.second.apply {
                                        this.data = data
                                        state = WatchState.Watching
                                    }
                                    resetTargets[entry.first] = data
                                    onStart(targetPath.originalPath)
                                }

                                WatchState.Stopping -> {
                                    // 登録解除
                                    stopWatching(targetPath)
                                }
                            }
                        }
                    }
                    resetTargets.forEach { entry ->
                        val targetDirectory = entry.key.originalPath
                        val data = checkNotNull(entry.value)
                        ResetEvent(data.eventHandle)
                        logger?.debug { "ReadDirectoryChangesW: $targetDirectory" }
                        val watchResult = ReadDirectoryChangesW(
                            hDirectory = data.directoryHandle,
                            lpBuffer = data.buffer,
                            nBufferLength = bufferSize,
                            bWatchSubtree = FALSE,
                            // 対象のディレクトリの子要素のどのイベントを受け取るか
                            dwNotifyFilter = (
                                    // ディレクトリの名前変更、作成、削除
                                    FILE_NOTIFY_CHANGE_DIR_NAME or
                                            // ファイル名の変更、作成、削除
                                            FILE_NOTIFY_CHANGE_FILE_NAME or
                                            // ファイル内容の変更
                                            FILE_NOTIFY_CHANGE_LAST_WRITE
                                    ).toUInt(),
                            lpBytesReturned = null,
                            lpOverlapped = data.overlapped.ptr,
                            lpCompletionRoutine = null
                        )
                        if (watchResult == FALSE) {
                            val errorCode = GetLastError()
                            when (errorCode.toInt()) {
                                ERROR_OPERATION_ABORTED -> {
                                    // CloseHandle() により監視終了
                                    // no operation
                                }

                                else -> {
                                    // その他のエラー
                                    onError(
                                        targetDirectory,
                                        "ReadDirectoryChangesW failed: $targetDirectory"
                                    )
                                }
                            }
                            // 監視停止したものを削除
                            stopWatching(entry.key)
                        }
                    }
                    resetTargets.clear()
                    targetStatuses.map {
                        it.key to checkNotNull(it.value.data)
                    }.toMap(watchTargets)
                    if (targetStatuses.isEmpty()) {
                        // スレッド終了処理
                        CloseHandle(resource.threadResetHandle)
                        logger?.debug { "threadResetHandle closed" }
                        threadResource = null
                    }
                }
                if (watchTargets.isEmpty()) {
                    // スレッド終了
                    break
                }
                val waitResult = memScoped {
                    val eventHandlesPointer = allocArrayOf(
                        listOf(checkNotNull(threadResetHandle))
                                + watchTargets.values.map { it.eventHandle }
                    )
                    // イベント発生まで待機
                    WaitForMultipleObjects(
                        nCount = (watchTargets.size + 1).toUInt(),
                        lpHandles = eventHandlesPointer,
                        bWaitAll = FALSE,
                        dwMilliseconds = INFINITE
                    )
                }
                if (waitResult in WAIT_OBJECT_0 until (WAIT_OBJECT_0 + MAXIMUM_WAIT_OBJECTS.toUInt())) {
                    if (waitResult == WAIT_OBJECT_0) {
                        // threadResetEvent
                        // 次のループへ進み、追加 handles を処理する
                        logger?.debug { "threadReset Event received" }
                        ResetEvent(threadResetHandle)
                    } else {
                        val index = (waitResult - WAIT_OBJECT_0 - 1U).toInt()
                        val target = watchTargets.asSequence().elementAt(index)
                        resetTargets[target.key] = target.value
                        GetOverlappedResult(
                            hFile = target.value.directoryHandle,
                            lpOverlapped = target.value.overlapped.ptr,
                            lpNumberOfBytesTransferred = bytesReturned.ptr,
                            bWait = FALSE
                        )
                        if (bytesReturned.value == 0U) {
                            // バッファー読み込み失敗
                            // buffer に情報が収まらなかったとき
                            onError(
                                target.key.originalPath,
                                "ReadDirectoryChangesW buffer overflow: ${target.key.originalPath}"
                            )
                            logger?.debug { "ReadDirectoryChangesW buffer overflow: ${target.key.originalPath}\"" }
                        } else {
                            var infoPointer = target.value.buffer.reinterpret<FILE_NOTIFY_INFORMATION>()
                            while(true) {
                                val info = infoPointer.pointed
                                val path = info.fileName().unixPath()
                                logger?.debug { "FILE_NOTIFY_INFORMATION event: ${info.toDebugString()}" }
                                // 監視対象とその子のイベントを検出
                                when (info.Action.toInt()) {
                                    FILE_ACTION_ADDED,
                                    FILE_ACTION_RENAMED_NEW_NAME -> {
                                        onEvent(
                                            target.key.originalPath,
                                            path,
                                            FileWatcherEvent.Create
                                        )
                                    }

                                    FILE_ACTION_REMOVED,
                                    FILE_ACTION_RENAMED_OLD_NAME-> {
                                        onEvent(
                                            target.key.originalPath,
                                            path,
                                            FileWatcherEvent.Delete
                                        )
                                    }

                                    FILE_ACTION_MODIFIED -> {
                                        onEvent(
                                            target.key.originalPath,
                                            path,
                                            FileWatcherEvent.Modify
                                        )
                                    }
                                }
                                if (info.NextEntryOffset == 0U) {
                                    break
                                } else {
                                    infoPointer = checkNotNull(interpretCPointer(
                                        infoPointer.rawValue + info.NextEntryOffset.toLong())
                                    )
                                }
                            }
                        }
                    }
                } else {
                    // シグナル監視エラー
                    // スレッドを終了させる
                    finishing = true
                    val errorCode = GetLastError()
                    onError(null, "WaitForMultipleObjects error: $errorCode")
                    logger?.error { "WaitForMultipleObjects error: $errorCode" }
                    continue
                }
            }
            logger?.debug { "watchingThread() finished" }
            if (disposing) {
                dispose()
            }
        }
    }

    actual fun close() {
        logger?.debug { "close()" }
        @OptIn(DelicateCoroutinesApi::class)
        GlobalScope.launch {
            val hasThread = withLock {
                val resource = threadResource
                if (resource != null) {
                    resource.disposing = true
                    true
                } else false
            }
            if (hasThread) {
                stopAll()
            } else {
                dispose()
            }
        }
    }

    private fun <U> withLock(block: () -> U): U {
        return try {
            EnterCriticalSection(criticalSectionPointer)
            block()
        } finally {
            LeaveCriticalSection(criticalSectionPointer)
        }
    }

    private fun dispose() {
        logger?.debug { "dispose()" }
        DeleteCriticalSection(criticalSectionPointer)
        nativeHeap.free(criticalSectionPointer)
        dispatcher.close()
    }

    private fun String.unixPath(): String {
        return replace("\\", "/")
    }

    private fun FILE_NOTIFY_INFORMATION.toDebugString(): String {
        val actionString = mapOf(
            FILE_ACTION_ADDED to "FILE_ACTION_ADDED", // The file was added to the directory.
            FILE_ACTION_REMOVED to "FILE_ACTION_REMOVED", // The file was removed from the directory.
            FILE_ACTION_MODIFIED to "FILE_ACTION_MODIFIED", // The file was modified.
            FILE_ACTION_RENAMED_OLD_NAME to "FILE_ACTION_RENAMED_OLD_NAME", // The file was renamed and this is the old name.
            FILE_ACTION_RENAMED_NEW_NAME to "FILE_ACTION_RENAMED_NEW_NAME" // The file was renamed and this is the new name.
        )[Action.toInt()]?.let {
            "${it}:0x${Action.toString(16)}"
        } ?: "unknown"
        val fileName = fileName()
        return "{Action=0x${Action.toString(16)}($actionString), FileName=${fileName}}"
    }

    private fun FILE_NOTIFY_INFORMATION.fileName(): String {
        return memScoped {
            allocArray<WCHARVar>(
                // NULL 終端文字列の + 1
                FileNameLength.toInt() + 1
            ).also {
                memcpy(it, FileName, FileNameLength.toULong())
            }.toKString()
        }
    }
}

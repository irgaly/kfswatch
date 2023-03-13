package io.github.irgaly.kfswatch.internal.platform

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.free
import kotlinx.cinterop.interpretCPointer
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import platform.posix.memcpy
import platform.windows.CRITICAL_SECTION
import platform.windows.CancelIo
import platform.windows.CloseHandle
import platform.windows.CreateEventW
import platform.windows.CreateFileW
import platform.windows.CreateThread
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
    private val criticalSectionPointer: CPointer<CRITICAL_SECTION> by lazy {
        val value = nativeHeap.alloc<CRITICAL_SECTION>()
        InitializeCriticalSection(value.ptr)
        value.ptr
    }
    private val handles: MutableMap<String, HANDLE> = mutableMapOf()
    private var threadResetHandle: HANDLE? = null

    actual fun start(targetDirectories: List<String>) {
        withLock {
            val handles = mutableMapOf<String, HANDLE>()
            for(targetDirectory in targetDirectories.subtract(this@FileWatcher.handles.keys)) {
                if (FileWatcherMaxTargets <= (this@FileWatcher.handles.size + handles.size)) {
                    onError(targetDirectory, "too many targets: max = $FileWatcherMaxTargets, cannot start watching $targetDirectory")
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
                val handle = checkNotNull(CreateFileW(
                    lpFileName = targetDirectory,
                    dwDesiredAccess = FILE_LIST_DIRECTORY,
                    dwShareMode = (FILE_SHARE_READ or FILE_SHARE_WRITE or FILE_SHARE_DELETE).toUInt(),
                    lpSecurityAttributes = null,
                    dwCreationDisposition = OPEN_EXISTING,
                    dwFlagsAndAttributes = (FILE_FLAG_BACKUP_SEMANTICS or FILE_FLAG_OVERLAPPED).toUInt(),
                    hTemplateFile = null
                ))
                if (handle == INVALID_HANDLE_VALUE) {
                    onError(targetDirectory, "cannot open target: $targetDirectory")
                    continue
                }
                handles[targetDirectory] = handle
                onStart(targetDirectory)
            }
            if (handles.isNotEmpty()) {
                this@FileWatcher.handles.putAll(handles)
                if (threadResetHandle != null) {
                    // 監視対象追加のためのリセット指示
                    SetEvent(threadResetHandle)
                } else {
                    // 監視スレッドの状態リセットイベント
                    threadResetHandle = CreateEventW(
                        lpEventAttributes = null,
                        bManualReset = TRUE,
                        bInitialState = FALSE,
                        lpName = null
                    )
                    // ファイル監視スレッドの起動
                    val thisRef = StableRef.create(this@FileWatcher)
                    CreateThread(
                        lpThreadAttributes = null,
                        dwStackSize = 0,
                        lpStartAddress = staticCFunction { data: COpaquePointer? ->
                            initRuntimeIfNeeded()
                            checkNotNull(data)
                            val receivedThisRef = data.asStableRef<FileWatcher>()
                            receivedThisRef.get().watchingThread()
                            receivedThisRef.dispose()
                        }.reinterpret(),
                        lpParameter = thisRef.asCPointer(),
                        dwCreationFlags = 0,
                        lpThreadId = null
                    ).also {
                        // Thread Handle は不要であるため Close しておく
                        CloseHandle(it)
                    }
                }
            }
        }
    }

    actual fun stop(targetDirectories: List<String>) {
        withLock {
            targetDirectories.forEach { targetDirectory ->
                val handle = handles[targetDirectory]
                if (handle != null) {
                    CancelIo(handle)
                    CloseHandle(handle)
                    onStop(targetDirectory)
                    handles.remove(targetDirectory)
                }
            }
        }
    }

    actual fun stopAll() {
        withLock {
            handles.forEach {
                CancelIo(it.value)
                CloseHandle(it.value)
                onStop(it.key)
            }
            handles.clear()
        }
    }

    private data class ReadDirectoryData(
        val directoryHandle: HANDLE,
        val eventHandle: HANDLE,
        val overlapped: OVERLAPPED,
        val buffer: COpaquePointer
    )

    private fun watchingThread() {
        memScoped {
            // DWORD = 32 bits = 4 bytes
            // DWORD * (1024 * 2 length) = 4 * 1024 * 2 = 8 KB
            val bufferLength = 1024 * 2
            val bufferSize = (sizeOf<DWORDVar>() * bufferLength).toUInt()
            val bytesReturned = alloc<DWORDVar>()
            val watchTargets = linkedMapOf<String, ReadDirectoryData>()
            val resetTargets = mutableMapOf<String, ReadDirectoryData>()
            var threadResetHandle: HANDLE? = null
            while (true) {
                withLock {
                    threadResetHandle = this@FileWatcher.threadResetHandle
                    this@FileWatcher.handles.keys.subtract(watchTargets.keys).forEach { targetDirectory ->
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
                        val data = ReadDirectoryData(
                            checkNotNull(this@FileWatcher.handles[targetDirectory]),
                            checkNotNull(eventHandle),
                            overlapped,
                            buffer
                        )
                        watchTargets[targetDirectory] = data
                        resetTargets[targetDirectory] = data
                    }
                    resetTargets.forEach { entry ->
                        ResetEvent(entry.value.eventHandle)
                        val watchResult = ReadDirectoryChangesW(
                            hDirectory = entry.value.directoryHandle,
                            lpBuffer = entry.value.buffer,
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
                            lpOverlapped = entry.value.overlapped.ptr,
                            lpCompletionRoutine = null)
                        if (watchResult == FALSE) {
                            val errorCode = GetLastError()
                            when (errorCode.toInt()) {
                                ERROR_OPERATION_ABORTED -> {
                                    // CloseHandle() により監視終了
                                    // no operation
                                }
                                else -> {
                                    // その他のエラー
                                    onError(entry.key, "ReadDirectoryChangesW failed: $${entry.key}")
                                    CloseHandle(entry.value.directoryHandle)
                                }
                            }
                            nativeHeap.free(entry.value.buffer)
                            nativeHeap.free(entry.value.overlapped)
                            CloseHandle(entry.value.eventHandle)
                            // 監視停止したものを削除
                            this@FileWatcher.handles.remove(entry.key)
                            watchTargets.remove(entry.key)
                        }
                    }
                    if (watchTargets.isEmpty()) {
                        // 監視対象なし、スレッド終了
                        CloseHandle(threadResetHandle)
                        threadResetHandle = null
                    }
                }
                if (watchTargets.isEmpty()) {
                    // スレッド終了
                    break
                }
                resetTargets.clear()
                val waitResult = memScoped {
                    val eventHandlesPointer = allocArrayOf(
                        listOf(checkNotNull(threadResetHandle))
                        + watchTargets.values.map { it.eventHandle }
                    )
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
                            onError(target.key, "ReadDirectoryChangesW buffer overflow: ${target.key}")
                        } else {
                            var infoPointer = target.value.buffer.reinterpret<FILE_NOTIFY_INFORMATION>()
                            while(true) {
                                val info = infoPointer.pointed
                                val path = memScoped {
                                    allocArray<WCHARVar>(
                                        // NULL 終端文字列の + 1
                                        info.FileNameLength.toInt() + 1
                                    ).also {
                                        memcpy(it, info.FileName, info.FileNameLength.toULong())
                                    }.toKString()
                                }.unixPath()
                                // 監視対象とその子のイベントを検出
                                when (info.Action.toInt()) {
                                    FILE_ACTION_ADDED,
                                    FILE_ACTION_RENAMED_NEW_NAME-> {
                                        onEvent(target.key, path, FileWatcherEvent.Create)
                                    }
                                    FILE_ACTION_REMOVED,
                                    FILE_ACTION_RENAMED_OLD_NAME-> {
                                        onEvent(target.key, path, FileWatcherEvent.Delete)
                                    }
                                    FILE_ACTION_MODIFIED -> {
                                        onEvent(target.key, path, FileWatcherEvent.Modify)
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
                    continue
                }
            }
        }
    }

    actual fun close() {
        val thisRef = StableRef.create(this)
        CreateThread(
            lpThreadAttributes = null,
            dwStackSize = 0,
            lpStartAddress = staticCFunction { data: COpaquePointer? ->
                initRuntimeIfNeeded()
                checkNotNull(data)
                val receivedThisRef = data.asStableRef<FileWatcher>()
                receivedThisRef.get().closeThread()
                receivedThisRef.dispose()
            }.reinterpret(),
            lpParameter = thisRef.asCPointer(),
            dwCreationFlags = 0,
            lpThreadId = null
        ).also {
            CloseHandle(it)
        }
    }

    private fun closeThread() {
        stopAll()
        DeleteCriticalSection(criticalSectionPointer)
        nativeHeap.free(criticalSectionPointer)
    }

    private fun withLock(block: () -> Unit) {
        try {
            EnterCriticalSection(criticalSectionPointer)
            block()
        } finally {
            LeaveCriticalSection(criticalSectionPointer)
        }
    }

    private fun String.unixPath(): String {
        return replace("\\", "/")
    }
}

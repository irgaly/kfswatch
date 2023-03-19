package io.github.irgaly.kfswatch.internal.platform

import kotlinx.cinterop.BooleanVar
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.cValuesOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSLock
import platform.Foundation.NSString
import platform.darwin.DISPATCH_QUEUE_SERIAL
import platform.darwin.EVFILT_READ
import platform.darwin.EVFILT_VNODE
import platform.darwin.EV_ADD
import platform.darwin.EV_CLEAR
import platform.darwin.EV_DELETE
import platform.darwin.EV_ERROR
import platform.darwin.NOTE_DELETE
import platform.darwin.NOTE_RENAME
import platform.darwin.NOTE_WRITE
import platform.darwin.dispatch_async
import platform.darwin.dispatch_queue_create
import platform.darwin.dispatch_queue_t
import platform.darwin.kevent
import platform.darwin.kqueue
import platform.posix.O_EVTONLY
import platform.posix.errno
import platform.posix.open
import platform.posix.pipe
import platform.posix.read
import platform.posix.strerror
import platform.posix.uintptr_t
import platform.posix.write

/**
 * Kernel Queues
 *
 * https://developer.apple.com/library/archive/documentation/Darwin/Conceptual/FSEvents_ProgGuide/KernelQueues/KernelQueues.html
 */
@OptIn(UnsafeNumber::class)
internal actual class FileWatcher actual constructor(
    private val onEvent: (targetDirectory: String, path: String, event: FileWatcherEvent) -> Unit,
    private val onStart: (targetDirectory: String) -> Unit,
    private val onStop: (targetDirectory: String) -> Unit,
    private val onError: (targetDirectory: String?, message: String) -> Unit,
    private val logger: Logger?
) {
    private val lock = NSLock()
    private val dispatchQueue = checkNotNull(
        dispatch_queue_create(
            label = "FileWatcher",
            attr = DISPATCH_QUEUE_SERIAL as dispatch_queue_t
        )
    )
    private var threadResource: ThreadResource? = null
    private val targetStatuses: MutableMap<String, WatchStatus> = mutableMapOf()

    private data class ThreadResource(
        val kqueue: Int,
        val threadResetPipeDescriptors: Pair<Int, Int>,
    )

    private data class WatchStatus(
        var descriptor: Int?,
        var state: WatchState
    )

    private enum class WatchState {
        Watching,
        Adding,
        Stopping
    }

    actual fun start(targetDirectories: List<String>) {
        withLock {
            var resource = threadResource
            for (targetDirectory in targetDirectories.subtract(
                targetStatuses.filter {
                    (it.value.state == WatchState.Watching || it.value.state == WatchState.Adding)
                }.map { it.key }.toSet()
            )) {
                if (FileWatcherMaxTargets <= targetStatuses.count {
                        (it.value.state == WatchState.Watching || it.value.state == WatchState.Adding)
                    }) {
                    onError(
                        targetDirectory,
                        "too many targets: max = $FileWatcherMaxTargets, cannot start watching $targetDirectory"
                    )
                    continue
                }
                val (exists: Boolean, isDirectory: Boolean) = memScoped {
                    val isDirectory = alloc<BooleanVar>()
                    val exists = NSFileManager.defaultManager.fileExistsAtPath(
                        targetDirectory,
                        isDirectory = isDirectory.ptr
                    )
                    Pair(exists, isDirectory.value)
                }
                if (!exists) {
                    onError(targetDirectory, "directory not exists: $targetDirectory")
                    continue
                }
                if (!isDirectory) {
                    onError(targetDirectory, "target is not directory: $targetDirectory")
                    continue
                }
                if (resource == null) {
                    val kqueue = kqueue()
                    if (kqueue < 0) {
                        val error = checkNotNull(strerror(errno)).toKString()
                        onError(targetDirectory, "kqueue() failed: $error")
                        continue
                    }
                    val threadResetPipeDescriptors = memScoped {
                        val pipeDescriptors = allocArray<IntVar>(2)
                        val pipeResult = pipe(pipeDescriptors)
                        if (pipeResult == -1) {
                            null
                        } else Pair(pipeDescriptors[0], pipeDescriptors[1])
                    }
                    if (threadResetPipeDescriptors == null) {
                        val error = checkNotNull(strerror(errno)).toKString()
                        onError(targetDirectory, "pipe() error: $error")
                        platform.posix.close(kqueue)
                        continue
                    }
                    memScoped {
                        // pipe をイベント登録
                        val event = alloc<kevent>()
                        event.apply {
                            ident = threadResetPipeDescriptors.first.convert()
                            filter = EVFILT_READ.toShort()
                            flags = (
                                EV_ADD or // イベント追加
                                EV_CLEAR // イベント受信後にイベントを自動リセット
                            ).toUShort()
                            fflags = 0U
                            data = 0
                            udata = null
                        }
                        kevent(
                            kq = kqueue,
                            changelist = event.ptr,
                            nchanges = 1,
                            eventlist = null,
                            nevents = 0,
                            timeout = null
                        )
                    }
                    resource = ThreadResource(kqueue, threadResetPipeDescriptors)
                }
                targetStatuses[targetDirectory] = WatchStatus(null, WatchState.Adding)
            }
            if (this.threadResource == null && resource != null) {
                this.threadResource = resource
                // kqueue 監視スレッドの起動
                dispatch_async(queue = dispatchQueue) {
                    this@FileWatcher.watchingThread()
                }
            }
        }
    }
    actual fun stop(targetDirectories: List<String>) {
        withLock {
            var changed = false
            targetDirectories.forEach { targetDirectory ->
                val status = targetStatuses[targetDirectory]
                if (status != null) {
                    when (status.state) {
                        WatchState.Watching -> {
                            status.state = WatchState.Stopping
                            changed = true
                        }
                        WatchState.Adding -> {
                            targetStatuses.remove(targetDirectory)
                        }
                        WatchState.Stopping -> {}
                    }
                }
            }
            if (changed) {
                // スレッドのリセット指示
                write(
                    __fd = checkNotNull(threadResource).threadResetPipeDescriptors.second,
                    __buf = cValuesOf(0.toByte()),
                    __nbyte = 1
                )
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
                write(
                    __fd = checkNotNull(threadResource).threadResetPipeDescriptors.second,
                    __buf = cValuesOf(0.toByte()),
                    __nbyte = 1
                )
            }
        }
    }

    private fun watchingThread() {
        memScoped {
            logger?.debug { "watchingThread() start" }
            val kevent = alloc<kevent>()
            var finishing = false
            data class ChildDescriptor(
                val targetDirectory: String,
                val path: String,
                val descriptor: Int
            )
            data class WatchInfo(
                val targetDirectory: String,
                val childDescriptors: MutableMap<String, ChildDescriptor> = mutableMapOf()
            )
            val watchInfo: MutableMap<String, WatchInfo> = mutableMapOf()
            var infoToRefresh: Pair<String, ChildDescriptor?>? = null
            while(true) {
                var kqueue: Int? = null
                var threadResetPipeDescriptor: Int? = null
                var descriptorsToTargetDirectory: Map<Int, String> = emptyMap()
                var descriptorsToWatchInfo: Map<Int, Pair<WatchInfo, ChildDescriptor>> = emptyMap()
                val finish = withLock {
                    val resource = checkNotNull(threadResource)
                    kqueue = resource.kqueue
                    threadResetPipeDescriptor = resource.threadResetPipeDescriptors.first
                    infoToRefresh?.let { refreshInfo ->
                        // 監視ディレクトリの子要素再列挙
                        val targetDirectory = refreshInfo.first
                        val status = checkNotNull(targetStatuses[targetDirectory])
                        if (status.state == WatchState.Watching) {
                            // 監視中の状態のみ再列挙対応
                            val info = checkNotNull(watchInfo[targetDirectory])
                            refreshInfo.second?.let { descriptor ->
                                // 削除された可能性のあるファイルを監視除外
                                unregisterKqueue(checkNotNull(kqueue), descriptor.descriptor)
                                info.childDescriptors.remove(descriptor.path)
                            }
                            val (children, listErrorMessage) = listChildren(targetDirectory)
                            if (listErrorMessage != null) {
                                // 読み取りエラーにより監視解除
                                onError(targetDirectory, "contentsOfDirectoryAtPath error: $listErrorMessage")
                                status.state = WatchState.Stopping
                            } else {
                                for (path in children) {
                                    if (!info.childDescriptors.contains(path)) {
                                        // 監視追加
                                        val descriptor = registerChild(
                                            checkNotNull(kqueue), targetDirectory, path
                                        ) { errorMessage: String ->
                                            onError(targetDirectory, "registerChild error: $errorMessage")
                                        }
                                        if (descriptor == null) {
                                            // 監視停止
                                            status.state = WatchState.Stopping
                                            break
                                        } else {
                                            info.childDescriptors[path] = ChildDescriptor(
                                                targetDirectory, path, descriptor
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        infoToRefresh = null
                    }
                    fun stopWatching(targetDirectory: String) {
                        unregisterKqueue(
                            checkNotNull(kqueue),
                            checkNotNull(targetStatuses[targetDirectory]?.descriptor)
                        )
                        checkNotNull(watchInfo[targetDirectory]).childDescriptors.forEach {
                            unregisterKqueue(checkNotNull(kqueue), it.value.descriptor)
                        }
                        watchInfo.remove(targetDirectory)
                        targetStatuses.remove(targetDirectory)
                        onStop(targetDirectory)
                    }
                    for(entry in targetStatuses.toList()) {
                        val targetDirectory = entry.first
                        if (finishing) {
                            // 終了処理
                            when (entry.second.state) {
                                WatchState.Adding -> {
                                    targetStatuses.remove(targetDirectory)
                                }
                                WatchState.Watching,
                                WatchState.Stopping -> {
                                    // 登録解除
                                    stopWatching(targetDirectory)
                                }
                            }
                        } else {
                            when (entry.second.state) {
                                WatchState.Watching -> {}
                                WatchState.Adding -> {
                                    val targetDescriptor = registerParent(
                                        checkNotNull(kqueue), targetDirectory
                                    ) { errorMessage ->
                                        onError(targetDirectory, "registerParent: $errorMessage")
                                    }
                                    if (targetDescriptor == null) {
                                        targetStatuses.remove(targetDirectory)
                                        continue
                                    }
                                    val (children, listErrorMessage) = listChildren(targetDirectory)
                                    if (listErrorMessage != null) {
                                        onError(targetDirectory, "contentsOfDirectoryAtPath error: $listErrorMessage")
                                        unregisterKqueue(checkNotNull(kqueue), targetDescriptor)
                                        targetStatuses.remove(targetDirectory)
                                        continue
                                    }
                                    val info = WatchInfo(targetDirectory)
                                    var childError = false
                                    for (path in children) {
                                        val descriptor = registerChild(
                                            checkNotNull(kqueue), targetDirectory, path
                                        ) { errorMessage ->
                                            onError(targetDirectory, "registerChild: $errorMessage")
                                            childError = true
                                        }
                                        if (descriptor == null) {
                                            break
                                        } else {
                                            info.childDescriptors[path] = ChildDescriptor(
                                                targetDirectory, path, descriptor
                                            )
                                        }
                                    }
                                    if (childError) {
                                        // 監視失敗
                                        info.childDescriptors.values.forEach {
                                            unregisterKqueue(checkNotNull(kqueue), it.descriptor)
                                        }
                                        unregisterKqueue(checkNotNull(kqueue), targetDescriptor)
                                        targetStatuses.remove(targetDirectory)
                                        continue
                                    }
                                    watchInfo[targetDirectory] = info
                                    entry.second.apply {
                                        descriptor = targetDescriptor
                                        state = WatchState.Watching
                                    }
                                    onStart(targetDirectory)
                                }
                                WatchState.Stopping -> {
                                    // 登録解除
                                    stopWatching(targetDirectory)
                                }
                            }
                        }
                    }
                    descriptorsToTargetDirectory = targetStatuses.entries.associate {
                        checkNotNull(it.value.descriptor)  to it.key
                    }
                    descriptorsToWatchInfo = watchInfo.values.flatMap { info ->
                        info.childDescriptors.values.map {
                            it.descriptor to Pair(info, it)
                        }
                    }.toMap()
                    if (targetStatuses.isEmpty()) {
                        platform.posix.close(resource.threadResetPipeDescriptors.first)
                        platform.posix.close(resource.threadResetPipeDescriptors.second)
                        platform.posix.close(resource.kqueue)
                        threadResource = null
                        true
                    } else false
                }
                if (finish) {
                    // スレッド終了
                    break
                }
                // イベント待機
                val eventCount = kevent(
                    kq = checkNotNull(kqueue),
                    changelist = null,
                    nchanges = 0,
                    eventlist = kevent.ptr,
                    nevents = 1,
                    timeout = null
                )
                if (eventCount < 0 || kevent.flags == EV_ERROR.toUShort()) {
                    // イベント監視エラー
                    // スレッドを終了させる
                    finishing = true
                    val error = checkNotNull(strerror(errno)).toKString()
                    onError(null, "waiting kevent failed: $error")
                    logger?.error { "waiting kevent failed: $error" }
                    continue
                }
                if (0 < eventCount) {
                    logger?.debug {
                        "kevent: event = $kevent"
                    }
                    if (kevent.ident == checkNotNull(threadResetPipeDescriptor).convert<uintptr_t>()) {
                        logger?.debug { "kevent: threadResetPipeDescriptor received" }
                        memScoped {
                            // スレッドリセット
                            // 1 bytes 読み捨てる
                            read(
                                /* __fd = */ checkNotNull(threadResetPipeDescriptor),
                                /* __buf = */ alloc<ByteVar>().ptr,
                                /* __nbytes = */ 1,
                            )
                        }
                    } else {
                        logger?.debug {
                            "kevent: ident=${kevent.ident} udata=${kevent.udata?.reinterpret<ByteVar>()?.toKString()}"
                        }
                        val descriptor = kevent.ident.toInt()
                        var (info, childDescriptor) = descriptorsToWatchInfo[descriptor] ?: Pair(null, null)
                        val targetDirectory = info?.targetDirectory ?: descriptorsToTargetDirectory[descriptor]
                        if (info == null && targetDirectory != null) {
                            info = watchInfo[targetDirectory]
                        }
                        // 監視イベントが遅れて発生したときには watchInfo == null となることがありえる
                        if (info != null) {
                            logger?.debug {
                                "          targetDirectory=${info.targetDirectory}"
                            }
                            if (childDescriptor == null) {
                                // 監視対象ディレクトリのイベント
                                if (kevent.fflags.toInt() == NOTE_WRITE) {
                                    // 監視対象ディレクトリの子要素が変化した
                                    // * 検出されそう
                                    //     * 子要素の追加
                                    //     * 子要素の削除
                                    //     * 子要素の rename
                                    infoToRefresh = Pair(info.targetDirectory, null)
                                }
                            } else {
                                // 監視対象ディレクトリの子要素のイベント
                                when (kevent.fflags.toInt()) {
                                    NOTE_WRITE -> {
                                        // 子要素のファイル・ディレクトリへの変更操作があった
                                        onEvent(info.targetDirectory, childDescriptor.path, FileWatcherEvent.Modify)
                                    }
                                    NOTE_DELETE -> {
                                        // 子要素のファイル・ディレクトリの unlink があった
                                        // 親ディレクトリの NOTE_WRITE は発生しそう
                                        onEvent(info.targetDirectory, childDescriptor.path, FileWatcherEvent.Delete)
                                        infoToRefresh = Pair(info.targetDirectory, childDescriptor)
                                    }
                                    NOTE_RENAME -> {
                                        // 子要素のファイル・ディレクトリの rename があった
                                        // 親ディレクトリの NOTE_WRITE は発生しそう
                                        onEvent(info.targetDirectory, childDescriptor.path, FileWatcherEvent.Delete)
                                        infoToRefresh = Pair(info.targetDirectory, childDescriptor)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            logger?.debug { "watchingThread() finished" }
        }
    }

    actual fun close() {
        dispatch_async(queue = dispatchQueue) {
            stopAll()
        }
    }

    private fun <U> withLock(block: () -> U): U {
        return try {
            lock.lock()
            block()
        } finally {
            lock.unlock()
        }
    }

    private fun registerParent(
        kqueue: Int,
        targetDirectory: String,
        onError: (message: String) -> Unit
    ): Int? {
        return memScoped {
            val descriptor = open(targetDirectory, O_EVTONLY)
            if (descriptor <= 0) {
                val error = checkNotNull(strerror(errno)).toKString()
                onError("open $targetDirectory : $error")
                null
            } else {
                val event = alloc<kevent>()
                event.apply {
                    ident = descriptor.convert<uintptr_t>()
                    filter = EVFILT_VNODE.toShort()
                    flags = (
                        EV_ADD or // イベント追加
                        EV_CLEAR // イベント受信後にイベントを自動リセット
                    ).toUShort()
                    fflags = NOTE_WRITE.toUInt() // ディレクトリの子要素の変更のみ監視
                    data = 0
                    udata = null
                }
                kevent(
                    kq = kqueue,
                    changelist = event.ptr,
                    nchanges = 1,
                    eventlist = null,
                    nevents = 0,
                    timeout = null
                )
                logger?.debug { "kevent registered: $targetDirectory, descriptor=$descriptor" }
                descriptor
            }
        }
    }

    private fun registerChild(
        kqueue: Int,
        targetDirectory: String,
        path: String,
        onError: (message: String) -> Unit
    ): Int? {
        return memScoped {
            val descriptor = open("$targetDirectory/$path", O_EVTONLY)
            if (descriptor <= 0) {
                val error = checkNotNull(strerror(errno)).toKString()
                onError("open $targetDirectory/$path : $error")
                null
            } else {
                val event = alloc<kevent>()
                event.apply {
                    ident = descriptor.convert<uintptr_t>()
                    filter = EVFILT_VNODE.toShort()
                    flags = (
                        EV_ADD or // イベント追加
                        EV_CLEAR // イベント受信後にイベントを自動リセット
                    ).toUShort()
                    fflags = (
                        NOTE_DELETE or // 対象の削除
                        NOTE_WRITE or // 対象の変更
                        NOTE_RENAME // 対象の名前変更
                    ).toUInt()
                    data = 0
                    udata = null
                }
                kevent(
                    kq = kqueue,
                    changelist = event.ptr,
                    nchanges = 1,
                    eventlist = null,
                    nevents = 0,
                    timeout = null
                )
                logger?.debug { "kevent registered: $targetDirectory/$path, descriptor=$descriptor" }
                descriptor
            }
        }
    }

    private fun unregisterKqueue(
        kqueue: Int,
        descriptor: Int
    ) {
        memScoped {
            val event = alloc<kevent>()
            event.apply {
                ident = descriptor.convert<uintptr_t>()
                filter = EVFILT_VNODE.toShort()
                flags = EV_DELETE.toUShort()
                fflags = 0U
                data = 0
                udata = null
            }
            kevent(
                kq = kqueue,
                changelist = event.ptr,
                nchanges = 1,
                eventlist = null,
                nevents = 0,
                timeout = null
            )
            platform.posix.close(descriptor)
            logger?.debug { "kevent unregistered: descriptor=$descriptor" }
        }
    }

    private fun listChildren(path: String): Pair<List<String>, String?> {
        return memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            @Suppress("UNCHECKED_CAST")
            val children = (NSFileManager.defaultManager.contentsOfDirectoryAtPath(
                path = path,
                error = error.ptr
            ) as List<NSString>?)?.map {
                @Suppress("CAST_NEVER_SUCCEEDS")
                it as String
            }
            Pair(children ?: emptyList(), error.value?.toString())
        }
    }
}

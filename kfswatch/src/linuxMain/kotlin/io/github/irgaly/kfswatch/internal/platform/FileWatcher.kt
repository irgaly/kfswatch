package io.github.irgaly.kfswatch.internal.platform

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alignOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.cValuesOf
import kotlinx.cinterop.free
import kotlinx.cinterop.get
import kotlinx.cinterop.interpretCPointer
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import platform.linux.IN_ACCESS
import platform.linux.IN_ATTRIB
import platform.linux.IN_CLOSE_NOWRITE
import platform.linux.IN_CLOSE_WRITE
import platform.linux.IN_CREATE
import platform.linux.IN_DELETE
import platform.linux.IN_DELETE_SELF
import platform.linux.IN_IGNORED
import platform.linux.IN_ISDIR
import platform.linux.IN_MODIFY
import platform.linux.IN_MOVED_FROM
import platform.linux.IN_MOVED_TO
import platform.linux.IN_MOVE_SELF
import platform.linux.IN_NONBLOCK
import platform.linux.IN_OPEN
import platform.linux.IN_Q_OVERFLOW
import platform.linux.IN_UNMOUNT
import platform.linux.inotify_add_watch
import platform.linux.inotify_event
import platform.linux.inotify_init1
import platform.linux.inotify_rm_watch
import platform.posix.EAGAIN
import platform.posix.NAME_MAX
import platform.posix.POLLIN
import platform.posix.S_IFDIR
import platform.posix.S_IFMT
import platform.posix.close
import platform.posix.errno
import platform.posix.pipe
import platform.posix.poll
import platform.posix.pollfd
import platform.posix.pthread_mutex_destroy
import platform.posix.pthread_mutex_init
import platform.posix.pthread_mutex_lock
import platform.posix.pthread_mutex_t
import platform.posix.pthread_mutex_unlock
import platform.posix.read
import platform.posix.sem_destroy
import platform.posix.sem_init
import platform.posix.sem_post
import platform.posix.sem_t
import platform.posix.sem_trywait
import platform.posix.sem_wait
import platform.posix.stat
import platform.posix.strerror
import platform.posix.write

/**
 * Linux inotify
 *
 * https://manpages.ubuntu.com/manpages/bionic/en/man7/inotify.7.html
 */
@OptIn(ExperimentalForeignApi::class)
internal actual class FileWatcher actual constructor(
    private val onEvent: (targetDirectory: String, path: String, event: FileWatcherEvent) -> Unit,
    private val onStart: (targetDirectory: String) -> Unit,
    private val onStop: (targetDirectory: String) -> Unit,
    private val onOverflow: (targetDirectory: String?) -> Unit,
    private val onError: (targetDirectory: String?, message: String) -> Unit,
    private val onRawEvent: ((event: FileWatcherRawEvent) -> Unit)?,
    private val logger: Logger?
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val dispatcher = newSingleThreadContext("FileWatcher")
    private val mutex: CPointer<pthread_mutex_t> by lazy {
        val value = nativeHeap.alloc<pthread_mutex_t>()
        pthread_mutex_init(
            __mutex = value.ptr,
            __mutexattr = null
        )
        value.ptr
    }
    private val threadSemaphore: CPointer<sem_t> by lazy {
        val value = nativeHeap.alloc<sem_t>()
        sem_init(
            __sem = value.ptr,
            __pshared = 0, /* 同一プロセス内セマフォ */
            __value = 1U /* セマフォ初期値はロック可能 */
        )
        value.ptr
    }
    private var threadResource: ThreadResource? = null
    private val targetStatuses: MutableMap<String, WatchStatus> = mutableMapOf()

    private data class ThreadResource(
        val inotifyFileDescriptor: Int,
        val threadResetPipeDescriptors: Pair<Int, Int>,
        var disposing: Boolean
    )

    private data class WatchStatus(
        var watchDescriptor: Int?,
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
                val (exists, isDirectory) = memScoped {
                    val status = alloc<stat>()
                    val statResult = stat(
                        __file = targetDirectory,
                        __buf = status.ptr
                    )
                    Pair(
                        (statResult == 0),
                        (status.st_mode.toInt() and S_IFMT) == S_IFDIR
                    )
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
                    val inotifyFileDescriptor = inotify_init1(
                        __flags = IN_NONBLOCK
                    )
                    if (inotifyFileDescriptor == -1) {
                        val error = checkNotNull(strerror(errno)).toKString()
                        onError(targetDirectory, "inotify_init() error: $error")
                        continue
                    }
                    val threadResetPipeDescriptors = memScoped {
                        val pipeDescriptors = allocArray<IntVar>(2)
                        val pipeResult = pipe(__pipedes = pipeDescriptors)
                        if (pipeResult == -1) {
                            null
                        } else Pair(pipeDescriptors[0], pipeDescriptors[1])
                    }
                    if (threadResetPipeDescriptors == null) {
                        val error = checkNotNull(strerror(errno)).toKString()
                        onError(targetDirectory, "pipe() error: $error")
                        close(__fd = inotifyFileDescriptor)
                        continue
                    }
                    logger?.debug {
                        "pipe opened: descriptors[${threadResetPipeDescriptors.first} - ${threadResetPipeDescriptors.second}]"
                    }
                    resource = ThreadResource(
                        inotifyFileDescriptor,
                        threadResetPipeDescriptors,
                        false
                    )
                }
                targetStatuses[targetDirectory] = WatchStatus(null, WatchState.Adding)
            }
            if (resource != null) {
                if (threadResource != null) {
                    // スレッドリセットイベント送信
                    logger?.debug { "send thread reset for adding" }
                    write(
                        __fd = checkNotNull(threadResource).threadResetPipeDescriptors.second,
                        __buf = cValuesOf(0.toByte()),
                        __n = 1U
                    )
                } else {
                    threadResource = resource
                    // inotify 監視スレッドの起動
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
                logger?.debug { "send thread reset" }
                write(
                    __fd = checkNotNull(threadResource).threadResetPipeDescriptors.second,
                    __buf = cValuesOf(0.toByte()),
                    __n = 1U
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
                logger?.debug { "send thread reset" }
                write(
                    __fd = checkNotNull(threadResource).threadResetPipeDescriptors.second,
                    __buf = cValuesOf(0.toByte()),
                    __n = 1U
                )
            }
        }
    }

    private fun watchingThread() {
        memScoped {
            logger?.debug { "watchingThread() start" }
            val pollDescriptorsSize = 2
            val pollDescriptors = allocArray<pollfd>(pollDescriptorsSize)
            pollDescriptors[0].events = POLLIN.toShort()
            pollDescriptors[1].events = POLLIN.toShort()
            val bufferSize = (sizeOf<inotify_event>() + NAME_MAX + 1)
            val buffer = alloc(size = bufferSize, align = alignOf<inotify_event>())
                .reinterpret<inotify_event>().ptr
            var finishing = false
            var disposing = false
            while (true) {
                var descriptorsToTargetDirectory: Map<Int, String> = emptyMap()
                val finish = withLock {
                    val resource = checkNotNull(threadResource)
                    pollDescriptors[0].fd = resource.threadResetPipeDescriptors.first
                    pollDescriptors[1].fd = resource.inotifyFileDescriptor
                    disposing = resource.disposing
                    fun stopWatching(targetDirectory: String) {
                        logger?.debug { "inotify_rm_watch: $targetDirectory" }
                        inotify_rm_watch(
                            __fd = resource.inotifyFileDescriptor,
                            __wd = checkNotNull(targetStatuses[targetDirectory]?.watchDescriptor)
                        )
                        targetStatuses.remove(targetDirectory)
                        onStop(targetDirectory)
                    }
                    for (entry in targetStatuses.toList()) {
                        if (entry.second.state != WatchState.Watching) {
                            logger?.debug { "status: $entry" }
                        }
                        val targetDirectory = entry.first
                        if (finishing || disposing) {
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
                                    logger?.debug { "inotify_add_watch: $targetDirectory" }
                                    val descriptor = inotify_add_watch(
                                        __fd = resource.inotifyFileDescriptor,
                                        __name = targetDirectory,
                                        __mask = (
                                                IN_CREATE
                                                        or IN_DELETE
                                                        or IN_MODIFY
                                                        or IN_MOVED_FROM
                                                        or IN_MOVED_TO
                                                ).toUInt()
                                    )
                                    if (descriptor == -1) {
                                        val error = checkNotNull(strerror(errno)).toKString()
                                        onError(
                                            targetDirectory,
                                            "inotify_add_watch() error: $error"
                                        )
                                        targetStatuses.remove(targetDirectory)
                                        continue
                                    }
                                    entry.second.apply {
                                        watchDescriptor = descriptor
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
                        checkNotNull(it.value.watchDescriptor) to it.key
                    }
                    if (targetStatuses.isEmpty()) {
                        // スレッド終了処理
                        close(__fd = resource.threadResetPipeDescriptors.first)
                        close(__fd = resource.threadResetPipeDescriptors.second)
                        close(__fd = resource.inotifyFileDescriptor)
                        logger?.debug {
                            "pipe closed: [${resource.threadResetPipeDescriptors.first} - ${resource.threadResetPipeDescriptors.second}]"
                        }
                        logger?.debug { "inotify descriptor closed" }
                        threadResource = null
                        true
                    } else false
                }
                if (finish) {
                    // スレッド終了
                    break
                }
                // threadMutex が unlock されるまで通知を止める
                sem_wait(__sem = threadSemaphore)
                sem_post(__sem = threadSemaphore)
                // イベント発生まで待機
                val pollResult = poll(
                    __fds = pollDescriptors,
                    __nfds = pollDescriptorsSize.toULong(),
                    __timeout = -1
                )
                if (0 < pollResult) {
                    if ((pollDescriptors[0].revents.toInt() and POLLIN) != 0) {
                        logger?.debug { "poll: threadResetPipeDescriptor received" }
                        // スレッドリセット
                        // threadResetPipeDescriptor を 1 bytes 読み捨て、処理を継続
                        read(
                            __fd = pollDescriptors[0].fd,
                            __buf = buffer,
                            __nbytes = 1U,
                        )
                    }
                    if ((pollDescriptors[1].revents.toInt() and POLLIN) != 0) {
                        val length = read(pollDescriptors[1].fd, buffer, bufferSize.toULong())
                        if (length <= 0) {
                            when (val errorCode = errno) {
                                EAGAIN -> {
                                    // 一時的な読み取り不可
                                    // no operation
                                    logger?.debug { "inotify EAGAIN" }
                                }
                                else -> {
                                    // イベント監視エラー
                                    // スレッドを終了させる
                                    finishing = true
                                    val error = checkNotNull(strerror(errorCode)).toKString()
                                    onError(null, "inotify read error: $error")
                                    logger?.error {
                                        "read inotify fileDescriptor error: $error"
                                    }
                                    continue
                                }
                            }
                        } else {
                            val bufferPointer = buffer.reinterpret<inotify_event>()
                            var offset = 0L
                            while(offset < length) {
                                val infoPointer = checkNotNull(
                                    interpretCPointer<inotify_event>(
                                        bufferPointer.rawValue + offset
                                    )
                                )
                                val info = infoPointer.pointed
                                logger?.debug { "inotify event: ${info.toDebugString()}" }
                                onRawEvent?.invoke(
                                    FileWatcherRawEvent.LinuxInotifyRawEvent(
                                        wd = info.wd,
                                        name = info.name.toKString(),
                                        mask = info.mask,
                                        len = info.len,
                                        cookie = info.cookie
                                    )
                                )
                                val targetDirectory = descriptorsToTargetDirectory[info.wd]
                                val path = info.name.toKString()
                                val mask = info.mask.toInt()
                                if (targetDirectory != null) {
                                    logger?.debug { "inotify event: -> targetDirectory=$targetDirectory" }
                                    if (((mask and IN_CREATE) == IN_CREATE) ||
                                        ((mask and IN_MOVED_TO) == IN_MOVED_TO)
                                    ) {
                                        onEvent(targetDirectory, path, FileWatcherEvent.Create)
                                    }
                                    if (((mask and IN_DELETE) == IN_DELETE) ||
                                        ((mask and IN_MOVED_FROM) == IN_MOVED_FROM)
                                    ) {
                                        onEvent(targetDirectory, path, FileWatcherEvent.Delete)
                                    }
                                    if ((mask and IN_MODIFY) == IN_MODIFY) {
                                        onEvent(targetDirectory, path, FileWatcherEvent.Modify)
                                    }
                                    if ((mask and IN_IGNORED) == IN_IGNORED) {
                                        // 監視対象が削除され inotify が停止した
                                        // 監視停止処理を実行する
                                        stop(listOf(targetDirectory))
                                    }
                                } else {
                                    if ((mask and IN_Q_OVERFLOW) == IN_Q_OVERFLOW) {
                                        // inotify queue 全体で、イベントがオーバーフローした
                                        // このとき wq = -1 がセットされていて、
                                        // targetDirectory は特定できない
                                        onOverflow(null)
                                    }
                                }
                                offset += (sizeOf<inotify_event>() + info.len.toLong())
                            }
                        }
                    }
                }
            }
            logger?.debug { "watchingThread() finished" }
            if (disposing) {
                dispose()
            }
        }
    }

    actual fun pause() {
        val result = sem_trywait(__sem = threadSemaphore)
        if (result == 0) {
            // ロックされていない状態からロックされた
            // スレッドのリセット指示
            logger?.debug { "send thread reset for pause" }
            write(
                __fd = checkNotNull(threadResource).threadResetPipeDescriptors.second,
                __buf = cValuesOf(0.toByte()),
                __n = 1U
            )
        }
    }

    actual fun resume() {
        sem_post(__sem = threadSemaphore)
    }

    actual fun close() {
        logger?.debug { "close()" }
        // close() は非ブロッキング実装
        // リソース解放のために GlobalScope を許容
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
            pthread_mutex_lock(mutex)
            block()
        } finally {
            pthread_mutex_unlock(mutex)
        }
    }

    private fun dispose() {
        logger?.debug { "dispose()" }
        pthread_mutex_destroy(__mutex = mutex)
        sem_destroy(__sem = threadSemaphore)
        nativeHeap.free(mutex)
        nativeHeap.free(threadSemaphore)
        dispatcher.close()
    }

    private fun inotify_event.toDebugString(): String {
        val maskString = listOf(
            IN_ACCESS to "IN_ACCESS", // File was accessed
            IN_ATTRIB to "IN_ATTRIB", // Metadata changed
            IN_CLOSE_WRITE to "IN_CLOSE_WRITE", // File opened for writing was closed
            IN_CLOSE_NOWRITE to "IN_CLOSE_NOWRITE", // File or directory not opened for writing was closed.
            IN_CREATE to "IN_CREATE", // File/directory created in watched directory
            IN_DELETE to "IN_DELETE", // File/directory deleted from watched directory
            IN_DELETE_SELF to "IN_DELETE_SELF", //Watched file/directory was itself deleted
            IN_MODIFY to "IN_MODIFY", // File was modified
            IN_MOVE_SELF to "IN_MOVE_SELF", // Watched file/directory was itself moved
            IN_MOVED_FROM to "IN_MOVED_FROM", // old filename when a file is renamed
            IN_MOVED_TO to "IN_MOVED_TO", // new filename when a file is renamed
            IN_OPEN to "IN_OPEN", // File or directory was opened
            IN_UNMOUNT to "IN_UNMOUNT", // Backing fs was unmounted
            IN_Q_OVERFLOW to "IN_Q_OVERFLOW", // Event queued overflowed
            IN_IGNORED to "IN_IGNORED", // File was ignored
            IN_ISDIR to "IN_ISDIR" // event occurred against dir
        ).filter {
            ((mask.toInt() and it.first) == it.first)
        }.map {
            "${it.second}:0x${it.first.toString(16)}"
        }.let {
            if (it.isEmpty()) {
                "x"
            } else it.joinToString(", ")
        }
        return "{mask=0x${mask.toString(16)}($maskString), name=${name.toKString()}}"
    }
}

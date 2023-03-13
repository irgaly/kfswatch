package io.github.irgaly.kfswatch.internal.platform

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.alignOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.asStableRef
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
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import platform.linux.IN_CREATE
import platform.linux.IN_DELETE
import platform.linux.IN_MODIFY
import platform.linux.IN_MOVED_FROM
import platform.linux.IN_MOVED_TO
import platform.linux.IN_NONBLOCK
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
import platform.posix.pthread_create
import platform.posix.pthread_detach
import platform.posix.pthread_mutex_destroy
import platform.posix.pthread_mutex_init
import platform.posix.pthread_mutex_lock
import platform.posix.pthread_mutex_t
import platform.posix.pthread_mutex_unlock
import platform.posix.pthread_self
import platform.posix.pthread_tVar
import platform.posix.read
import platform.posix.stat
import platform.posix.strerror
import platform.posix.write

/**
 * Linux inotify
 *
 * https://manpages.ubuntu.com/manpages/bionic/en/man7/inotify.7.html
 */
internal actual class FileWatcher actual constructor(
    private val onEvent: (targetDirectory: String, path: String, event: FileWatcherEvent) -> Unit,
    private val onStart: (targetDirectory: String) -> Unit,
    private val onStop: (targetDirectory: String) -> Unit,
    private val onError: (targetDirectory: String?, message: String) -> Unit,
    private val logger: Logger?
) {
    private val mutex: CPointer<pthread_mutex_t> by lazy {
        val value = nativeHeap.alloc<pthread_mutex_t>()
        pthread_mutex_init(
            __mutex = value.ptr,
            __mutexattr = null
        )
        value.ptr
    }
    private var threadResource: ThreadResource? = null
    private val watchDescriptors: MutableMap<String, Int> = mutableMapOf()

    private data class ThreadResource(
        val inotifyFileDescriptor: Int,
        val threadResetPipeDescriptors: Pair<Int, Int>,
    )

    actual fun start(targetDirectories: List<String>) {
        withLock {
            var resource = threadResource
            for(targetDirectory in targetDirectories.subtract(watchDescriptors.keys)) {
                if (FileWatcherMaxTargets <= watchDescriptors.size) {
                    onError(targetDirectory, "too many targets: max = $FileWatcherMaxTargets, cannot start watching $targetDirectory")
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
                        val errorCode = errno
                        onError(targetDirectory, "inotify_init() error: $errorCode")
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
                        val errorCode = errno
                        onError(targetDirectory, "pipe() error: $errorCode")
                        close(__fd = inotifyFileDescriptor)
                        continue
                    }
                    resource = ThreadResource(inotifyFileDescriptor, threadResetPipeDescriptors)
                }
                val watchDescriptor = inotify_add_watch(
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
                if (watchDescriptor == -1) {
                    val errorCode = errno
                    onError(targetDirectory, "inotify_add_watch() error: $errorCode")
                    continue
                }
                watchDescriptors[targetDirectory] = watchDescriptor
                onStart(targetDirectory)
            }
            if (threadResource == null && resource != null) {
                threadResource = resource
                // inotify 監視スレッドの起動
                memScoped {
                    val thisRef = StableRef.create(this)
                    pthread_create(
                        __newthread = alloc<pthread_tVar>().ptr,
                        __attr = null,
                        __start_routine = staticCFunction { data: COpaquePointer? ->
                            initRuntimeIfNeeded()
                            pthread_detach(pthread_self())
                            checkNotNull(data)
                            val receivedThisRef = data.asStableRef<FileWatcher>()
                            receivedThisRef.get().watchingThread()
                            receivedThisRef.dispose()
                        }.reinterpret(),
                        __arg = thisRef.asCPointer()
                    )
                }
            }
        }
    }

    actual fun stop(targetDirectories: List<String>) {
        withLock {
            targetDirectories.forEach { targetDirectory ->
                val watchDescriptor = watchDescriptors[targetDirectory]
                if (watchDescriptor != null) {
                    inotify_rm_watch(
                        __fd = checkNotNull(threadResource).inotifyFileDescriptor,
                        __wd = watchDescriptor
                    )
                    onStop(targetDirectory)
                    watchDescriptors.remove(targetDirectory)
                }
            }
            if (watchDescriptors.isEmpty()) {
                threadResource?.threadResetPipeDescriptors?.second?.let {
                    // スレッドのリセット指示
                    write(
                        __fd = it,
                        __buf = cValuesOf(0.toByte()),
                        __n = 1
                    )
                }
            }
        }
    }

    actual fun stopAll() {
        withLock {
            watchDescriptors.forEach {
                inotify_rm_watch(
                    __fd = checkNotNull(threadResource).inotifyFileDescriptor,
                    __wd = it.value
                )
                onStop(it.key)
            }
            watchDescriptors.clear()
            threadResource?.threadResetPipeDescriptors?.second?.let {
                // スレッドのリセット指示
                write(
                    __fd = it,
                    __buf = cValuesOf(0.toByte()),
                    __n = 1
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
            var watchDirectories: Map<Int, String> = emptyMap()
            while (true) {
                val finish = withLock {
                    val resource = checkNotNull(threadResource)
                    pollDescriptors[0].fd = resource.threadResetPipeDescriptors.first
                    pollDescriptors[1].fd = resource.inotifyFileDescriptor
                    watchDirectories = watchDescriptors.entries.associate { (key, value) -> value to key }
                    if (watchDescriptors.isEmpty()) {
                        // スレッド終了処理
                        close(__fd = resource.threadResetPipeDescriptors.first)
                        close(__fd = resource.threadResetPipeDescriptors.second)
                        close(__fd = resource.inotifyFileDescriptor)
                        threadResource = null
                        true
                    } else false
                }
                if (finish) {
                    // スレッド終了
                    break
                }
                // イベント発生まで待機
                val pollResult = poll(
                    __fds = pollDescriptors,
                    __nfds = pollDescriptorsSize.toULong(),
                    __timeout = -1
                )
                if (0 < pollResult) {
                    if ((pollDescriptors[0].revents.toInt() and POLLIN) != 0) {
                        logger?.debug { "kevent: threadResetPipeDescriptor received" }
                        // スレッドリセット
                        // threadResetPipeDescriptor を 1 bytes 読み捨て、処理を継続
                        read(
                            __fd = pollDescriptors[0].fd,
                            __buf = buffer,
                            __nbytes = 1,
                        )
                    }
                    if ((pollDescriptors[1].revents.toInt() and POLLIN) != 0) {
                        val length = read(pollDescriptors[1].fd, buffer, bufferSize.toULong())
                        if (length <= 0) {
                            when (val errorCode = errno) {
                                EAGAIN -> {
                                    // 一時的な読み取り不可
                                    // no operation
                                }
                                else -> {
                                    logger?.error {
                                        val error = checkNotNull(strerror(errorCode)).toKString()
                                        "read inotify fileDescriptor error: $error"
                                    }
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
                                val targetDirectory = checkNotNull(watchDirectories[info.wd])
                                val path = info.name.toKString()
                                val mask = info.mask.toInt()
                                logger?.debug { "inotify event: $targetDirectory, name=$path, mask=${mask.toString(2)}" }
                                if (((mask and IN_CREATE) != 0) ||
                                    ((mask and IN_MOVED_TO) != 0)) {
                                    onEvent(targetDirectory, path, FileWatcherEvent.Create)
                                }
                                if (((mask and IN_DELETE) != 0) ||
                                    ((mask and IN_MOVED_FROM) != 0)) {
                                    onEvent(targetDirectory, path, FileWatcherEvent.Delete)
                                }
                                if ((mask and IN_MODIFY) != 0) {
                                    onEvent(targetDirectory, path, FileWatcherEvent.Modify)
                                }
                                offset += sizeOf<inotify_event>() + info.len.toLong()
                            }
                        }
                    }
                }
            }
            logger?.debug { "watchingThread() finished" }
        }
    }

    actual fun close() {
        memScoped {
            val thisRef = StableRef.create(this)
            pthread_create(
                __newthread = alloc<pthread_tVar>().ptr,
                __attr = null,
                __start_routine = staticCFunction { data: COpaquePointer? ->
                    initRuntimeIfNeeded()
                    pthread_detach(pthread_self())
                    checkNotNull(data)
                    val receivedThisRef = data.asStableRef<FileWatcher>()
                    receivedThisRef.get().closeThread()
                    receivedThisRef.dispose()
                }.reinterpret(),
                __arg = thisRef.asCPointer()
            )
        }
    }

    private fun closeThread() {
        stopAll()
        pthread_mutex_destroy(__mutex = mutex)
        nativeHeap.free(mutex)
    }

    private fun <U> withLock(block: () -> U): U {
        return try {
            pthread_mutex_lock(mutex)
            block()
        } finally {
            pthread_mutex_unlock(mutex)
        }
    }
}

package io.github.irgaly.test.platform

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

private val fs: dynamic get() = js("require('fs').promises")
private val fsSync: dynamic get() = js("require('fs')")
private val path: dynamic get() = js("require('path')")
private val os: dynamic get() = js("require('os')")

actual class Files {
    actual companion object {
        actual suspend fun createTemporaryDirectory(): String = suspendCoroutine { continuation ->
            if (isBrowser()) {
                continuation.resume("js-browser-dummy-temporary-directory")
            } else {
                @Suppress("UnsafeCastFromDynamic")
                fs.mkdtemp(path.join(os.tmpdir(), "temp_")).then { path ->
                    continuation.resume(path.unsafeCast<String>())
                }.catch { error ->
                    continuation.resumeWithException(IllegalStateException(error.message.unsafeCast<String>()))
                }
            }
        }

        actual fun createTemporaryDirectorySync(): String {
            return if (isBrowser()) {
                "js-browser-dummy-temporary-directory"
            } else {
                @Suppress("UnsafeCastFromDynamic")
                fsSync.mkdtempSync(path.join(os.tmpdir(), "temp_")).unsafeCast<String>()
            }
        }

        actual suspend fun createDirectory(path: String): Boolean =
            suspendCoroutine { continuation ->
                if (isBrowser()) {
                    continuation.resume(true)
                } else {
                    @Suppress("UnsafeCastFromDynamic")
                    fs.mkdir(path).then {
                        continuation.resume(true)
                    }.catch {
                        continuation.resume(false)
                    }
                }
            }

        actual suspend fun writeFile(path: String, text: String): Boolean =
            suspendCoroutine { continuation ->
                if (isBrowser()) {
                    continuation.resume(true)
                } else {
                    // change イベントのためになるべく上書きで更新する
                    // * macOS ではこの処理でも rename イベントになってしまう
                    @Suppress("UnsafeCastFromDynamic")
                    fs.open(path, "r+").then { handle ->
                        handle.write(text, 0).then { result ->
                            handle.truncate(result.bytesWritten)
                        }.then {
                            handle.close()
                        }
                    }.then {
                        continuation.resume(true)
                    }.catch {
                        fs.writeFile(path, text).then {
                            continuation.resume(true)
                        }.catch {
                            continuation.resume(false)
                        }
                    }
                }
            }

        actual suspend fun move(source: String, destination: String): Boolean =
            suspendCoroutine { continuation ->
                // destination が空の directory なら先に削除する
                @Suppress("UnsafeCastFromDynamic")
                fs.rmdir(destination).finally {
                    fs.rename(source, destination).then {
                        continuation.resume(true)
                    }.catch {
                        continuation.resume(false)
                    }
                }
            }

        actual suspend fun deleteRecursively(path: String): Boolean =
            suspendCoroutine { continuation ->
                if (isBrowser()) {
                    continuation.resume(true)
                } else {
                    @Suppress("UnsafeCastFromDynamic")
                    fs.rm(path, js("{recursive: true, force: true}")).then {
                        continuation.resume(true)
                    }.catch {
                        continuation.resume(false)
                    }
                }
            }
    }
}


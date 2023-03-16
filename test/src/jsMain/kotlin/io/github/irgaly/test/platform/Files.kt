package io.github.irgaly.test.platform

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

private val fs: dynamic get() = js("require('fs').promises")
private val path: dynamic get() = js("require('path')")
private val os: dynamic get() = js("require('os')")

actual class Files {
    actual companion object {
        actual suspend fun createTemporaryDirectory(): String = suspendCoroutine { continuation ->
            if (isBrowser()) {
                continuation.resume("js-browser-dummy-temporary-directory")
            } else {
                @Suppress("UnsafeCastFromDynamic")
                fs.mkdtemp(path.join(os.tmpdir(), "")).then { path ->
                    continuation.resume(path.unsafeCast<String>())
                }.catch { error ->
                    continuation.resumeWithException(IllegalStateException(error.message.unsafeCast<String>()))
                }
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
                    @Suppress("UnsafeCastFromDynamic")
                    fs.writeFile(path, text).then {
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


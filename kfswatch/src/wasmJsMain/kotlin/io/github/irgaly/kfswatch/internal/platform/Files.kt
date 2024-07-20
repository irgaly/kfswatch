package io.github.irgaly.kfswatch.internal.platform

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.js.Promise

private fun fs(): FsPromises = js("require('fs').promises")

private external interface FsPromises: JsAny {
    fun access(path: String): Promise<Nothing?>
    fun mkdir(path: String, options: JsAny): Promise<JsString>
}

private fun fsMkdirOptions(): JsAny = js("({recursive: true})")

internal actual class Files {
    actual companion object {
        actual suspend fun exists(path: String): Boolean = suspendCoroutine { continuation ->
            if (isBrowser()) {
                continuation.resumeWithException(UnsupportedOperationException("browser js cannot access to File Storage"))
            } else {
                val fs = fs()
                fs.access(path).then {
                    continuation.resume(true)
                    it
                }.catch {
                    continuation.resume(false)
                    it
                }
            }
        }

        actual suspend fun mkdirs(directoryPath: String): Boolean =
            suspendCoroutine { continuation ->
                if (isBrowser()) {
                    continuation.resumeWithException(UnsupportedOperationException("browser js cannot access to File Storage"))
                } else {
                    val fs = fs()
                    fs.mkdir(directoryPath, fsMkdirOptions()).then {
                        continuation.resume(true)
                        it
                    }.catch {
                        continuation.resume(false)
                        it
                    }
                }
            }

        actual val separator: String = "/"
    }
}

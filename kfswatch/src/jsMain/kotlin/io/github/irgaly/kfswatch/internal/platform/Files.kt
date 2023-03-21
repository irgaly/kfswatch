package io.github.irgaly.kfswatch.internal.platform

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

private val fs: dynamic get() = js("require('fs').promises")

internal actual class Files {
    actual companion object {
        actual suspend fun exists(path: String): Boolean = suspendCoroutine { continuation ->
            if (isBrowser()) {
                continuation.resumeWithException(UnsupportedOperationException("browser js cannot access to File Storage"))
            } else {
                @Suppress("UnsafeCastFromDynamic")
                fs.access(path).then {
                    continuation.resume(true)
                }.catch {
                    continuation.resume(false)
                }
            }
        }

        actual suspend fun mkdirs(directoryPath: String): Boolean =
            suspendCoroutine { continuation ->
                if (isBrowser()) {
                    continuation.resumeWithException(UnsupportedOperationException("browser js cannot access to File Storage"))
                } else {
                    @Suppress("UnsafeCastFromDynamic")
                    fs.mkdir(directoryPath, js("{recursive: true}")).then {
                        continuation.resume(true)
                    }.catch {
                        continuation.resume(false)
                    }
                }
            }

        actual val separator: String = "/"
    }
}

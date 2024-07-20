package io.github.irgaly.test.platform

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.js.Promise

private fun fs(): FsPromises = js("require('fs').promises")
private fun fsSync(): FsSync = js("require('fs')")
private fun path(): Path = js("require('path')")
private fun os(): Os = js("require('os')")

private external interface FsPromises: JsAny {
    fun mkdtemp(prefix: String): Promise<JsString>
    fun mkdir(path: String): Promise<Nothing?>
    fun rmdir(path: String): Promise<Nothing?>
    fun rename(oldPath: String, newPath: String): Promise<Nothing?>
    fun rm(path: String, options: JsAny): Promise<Nothing?>
    fun open(path: String, flags: String): Promise<FileHandle>
    fun writeFile(file: String, data: String): Promise<Nothing?>
}

private external interface FsSync: JsAny {
    fun mkdtempSync(prefix: String): String
}

private external interface FileHandle: JsAny {
    fun write(buffer: String, position: Int): Promise<WriteResult>
    fun truncate(len: Int): Promise<Nothing?>
    fun close(): Promise<Nothing?>
}

private external interface WriteResult: JsAny {
    val bytesWritten: Int
}

private external interface Path: JsAny {
    fun join(path1: String, path2: String): String
}

private external interface Os: JsAny {
    fun tmpdir(): String
}

private external interface Error: JsAny {
    val message: JsString
}

private fun fsRmOptions(): JsAny = js("{recursive: true, force: true}")

actual class Files {
    actual companion object {
        actual suspend fun createTemporaryDirectory(): String = suspendCoroutine { continuation ->
            if (isBrowser()) {
                continuation.resume("js-browser-dummy-temporary-directory")
            } else {
                val fs = fs()
                val path = path()
                val os = os()
                fs.mkdtemp(path.join(os.tmpdir(), "temp_")).then { path ->
                    continuation.resume(path.toString())
                    path
                }.catch { error ->
                    @Suppress("UNCHECKED_CAST_TO_EXTERNAL_INTERFACE")
                    continuation.resumeWithException(IllegalStateException((error as Error).message.toString()))
                    error
                }
            }
        }

        actual fun createTemporaryDirectorySync(): String {
            return if (isBrowser()) {
                "js-browser-dummy-temporary-directory"
            } else {
                val fsSync = fsSync()
                val path = path()
                val os = os()
                fsSync.mkdtempSync(path.join(os.tmpdir(), "temp_"))
            }
        }

        actual suspend fun createDirectory(path: String): Boolean =
            suspendCoroutine { continuation ->
                if (isBrowser()) {
                    continuation.resume(true)
                } else {
                    val fs = fs()
                    fs.mkdir(path).then {
                        continuation.resume(true)
                        it
                    }.catch {
                        continuation.resume(false)
                        it
                    }
                }
            }

        actual suspend fun writeFile(path: String, text: String): Boolean =
            suspendCoroutine { continuation ->
                if (isBrowser()) {
                    continuation.resume(true)
                } else {
                    val fs = fs()
                    // change イベントのためになるべく上書きで更新する
                    // * macOS ではこの処理でも rename イベントになってしまう
                    fs.open(path, "r+").then { handle ->
                        handle.write(text, 1).then { result ->
                            handle.truncate(result.bytesWritten)
                        }.then {
                            handle.close()
                        }
                    }.then {
                        continuation.resume(true)
                        it
                    }.catch {
                        fs.writeFile(path, text).then {
                            continuation.resume(true)
                            it
                        }.catch {
                            continuation.resume(false)
                            it
                        }
                    }
                }
            }

        actual suspend fun move(source: String, destination: String): Boolean =
            suspendCoroutine { continuation ->
                val fs = fs()
                // destination が空の directory なら先に削除する
                fs.rmdir(destination).finally {
                    fs.rename(source, destination).then {
                        continuation.resume(true)
                        it
                    }.catch {
                        continuation.resume(false)
                        it
                    }
                }
            }

        actual suspend fun deleteRecursively(path: String): Boolean =
            suspendCoroutine { continuation ->
                if (isBrowser()) {
                    continuation.resume(true)
                } else {
                    val fs = fs()
                    fs.rm(path, fsRmOptions()).then {
                        continuation.resume(true)
                        it
                    }.catch {
                        continuation.resume(false)
                        it
                    }
                }
            }
    }
}


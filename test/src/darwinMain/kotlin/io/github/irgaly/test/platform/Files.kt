package io.github.irgaly.test.platform

import kotlinx.cinterop.BooleanVar
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSItemReplacementDirectory
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.temporaryDirectory
import platform.Foundation.writeToFile
import platform.posix.rmdir

actual class Files {
    @OptIn(UnsafeNumber::class)
    actual companion object {
        actual suspend fun createTemporaryDirectory(): String = withContext(Dispatchers.Default) {
            createTemporaryDirectorySync()
        }

        actual fun createTemporaryDirectorySync(): String {
            return memScoped {
                // https://developer.apple.com/documentation/foundation/1409211-nstemporarydirectory
                // iOS: NSTemporaryDirectory() = (Application Sandbox)/tmp
                // macOS: NSTemporaryDirectory() = /var/folders/...
                // https://developer.apple.com/documentation/foundation/filemanager/1407693-url
                val manager = NSFileManager.defaultManager
                val errorPtr = alloc<ObjCObjectVar<NSError?>>().ptr
                val result = manager.URLForDirectory(
                    directory = NSItemReplacementDirectory,
                    inDomain = NSUserDomainMask,
                    appropriateForURL = manager.temporaryDirectory,
                    create = true,
                    error = errorPtr
                )?.path
                val error = errorPtr.pointed.value
                if (error != null) {
                    throw Exception(error.toString())
                }
                checkNotNull(result)
            }
        }

        actual suspend fun createDirectory(path: String): Boolean =
            withContext(Dispatchers.Default) {
                memScoped {
                    val manager = NSFileManager.defaultManager
                    val error = alloc<ObjCObjectVar<NSError?>>()
                    manager.createDirectoryAtURL(
                        url = NSURL(fileURLWithPath = path),
                        withIntermediateDirectories = false,
                        attributes = null,
                        error = error.ptr
                    )
                    (error.value == null)
                }
            }

        actual suspend fun writeFile(path: String, text: String): Boolean =
            withContext(Dispatchers.Default) {
                @Suppress("CAST_NEVER_SUCCEEDS")
                (path as NSString).writeToFile(
                    path = path,
                    atomically = false,
                    encoding = NSUTF8StringEncoding,
                    error = null
                )
            }

        actual suspend fun move(source: String, destination: String): Boolean =
            withContext(Dispatchers.Default) {
                val manager = NSFileManager.defaultManager
                val (exists: Boolean, isDirectory: Boolean) = memScoped {
                    val isDirectory = alloc<BooleanVar>()
                    val exists = manager.fileExistsAtPath(
                        destination,
                        isDirectory = isDirectory.ptr
                    )
                    Pair(exists, isDirectory.value)
                }
                if (exists) {
                    if (isDirectory) {
                        // destination が空ディレクトリであれば削除する
                        rmdir(destination)
                    } else {
                        // destination がファイルであれば削除する
                        manager.removeItemAtPath(
                            path = destination,
                            error = null
                        )
                    }
                }
                // https://developer.apple.com/documentation/foundation/nsfilemanager/1413529-moveitematpath
                // destination が存在するとエラー
                val result = manager.moveItemAtPath(
                    srcPath = source,
                    toPath = destination,
                    error = null
                )
                result
            }

        actual suspend fun deleteRecursively(path: String): Boolean =
            withContext(Dispatchers.Default) {
                memScoped {
                    // https://developer.apple.com/documentation/foundation/nsfilemanager/1413590-removeitematurl
                    val manager = NSFileManager.defaultManager
                    val errorPtr = alloc<ObjCObjectVar<NSError?>>().ptr
                    val removed = manager.removeItemAtURL(
                        URL = NSURL(fileURLWithPath = path),
                        error = errorPtr
                    )
                    val error = errorPtr.pointed.value
                    if (error != null) {
                        throw Exception(error.toString())
                    }
                    removed
                }
            }
    }
}

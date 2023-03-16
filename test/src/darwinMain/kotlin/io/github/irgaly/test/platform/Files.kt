package io.github.irgaly.test.platform

import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSItemReplacementDirectory
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.temporaryDirectory
import platform.Foundation.writeToFile

actual class Files {
    actual companion object {
        actual fun createTemporaryDirectory(): String {
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

        actual fun createDirectory(path: String): Boolean {
            return memScoped {
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

        actual fun writeFile(path: String, text: String): Boolean {
            return (path as NSString).writeToFile(
                path = path,
                atomically = false,
                encoding = NSUTF8StringEncoding,
                error = null
            )
        }

        actual fun deleteRecursively(path: String): Boolean {
            return memScoped {
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

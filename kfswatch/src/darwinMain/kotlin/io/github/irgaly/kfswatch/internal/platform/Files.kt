package io.github.irgaly.kfswatch.internal.platform

import kotlinx.cinterop.*
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL

internal actual class Files {
    actual companion object {
        actual fun exists(path: String): Boolean {
            return NSFileManager.defaultManager.fileExistsAtPath(path)
        }

        actual fun mkdirs(directoryPath: String): Boolean {
            return memScoped {
                // https://developer.apple.com/documentation/foundation/filemanager/1415371-createdirectory
                val manager = NSFileManager.defaultManager
                val errorPtr = alloc<ObjCObjectVar<NSError?>>().ptr
                val created = manager.createDirectoryAtURL(
                    url = NSURL(fileURLWithPath = directoryPath),
                    withIntermediateDirectories = true,
                    attributes = null,
                    error = errorPtr
                )
                val error = errorPtr.pointed.value
                if (error != null) {
                    throw Exception(error.toString())
                }
                created
            }
        }

        actual val separator: String = "/"
    }
}

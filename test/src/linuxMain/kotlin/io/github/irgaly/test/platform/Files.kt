package io.github.irgaly.test.platform

import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toCValues
import kotlinx.cinterop.toKString
import platform.posix.FTW_DEPTH
import platform.posix.FTW_PHYS
import platform.posix.O_CREAT
import platform.posix.O_TRUNC
import platform.posix.O_WRONLY
import platform.posix.close
import platform.posix.getenv
import platform.posix.mkdir
import platform.posix.mkdtemp
import platform.posix.nftw
import platform.posix.open
import platform.posix.remove
import platform.posix.write

actual class Files {
    actual companion object {
        actual fun createTemporaryDirectory(): String {
            val tempDirectory =
                sequenceOf("TMPDIR", "TMP", "TEMP", "TEMPDIR").firstNotNullOfOrNull {
                    getenv(it)?.toKString()
                } ?: "/tmp"
            val directory = mkdtemp("$tempDirectory/tmpdir.XXXXXX".cstr)?.toKString()
            return checkNotNull(directory)
        }

        actual fun createDirectory(path: String): Boolean {
            val result = mkdir(__path = path, __mode = 755)
            return (result == 0)
        }

        actual fun writeFile(path: String, text: String): Boolean {
            return memScoped {
                val fileDescriptor = open(
                    __file = path,
                    __oflag = (O_WRONLY or O_CREAT or O_TRUNC)
                )
                try {
                    val bytes = text.encodeToByteArray().toCValues()
                    val result = write(
                        __fd = fileDescriptor,
                        __buf = bytes,
                        __n = bytes.size.toULong()
                    )
                    (0 <= result)
                } finally {
                    close(fileDescriptor)
                }
            }
        }

        actual fun deleteRecursively(path: String): Boolean {
            val ret = nftw(
                path,
                staticCFunction { pathName, _, _, _ ->
                    remove(checkNotNull(pathName).toKString())
                },
                64,
                FTW_DEPTH or FTW_PHYS
            )
            return (ret != -1)
        }
    }
}

package io.github.irgaly.test.platform

import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toCValues
import kotlinx.cinterop.toKString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import platform.posix.rename
import platform.posix.write

actual class Files {
    actual companion object {
        actual suspend fun createTemporaryDirectory(): String = withContext(Dispatchers.Default) {
            createTemporaryDirectorySync()
        }

        actual fun createTemporaryDirectorySync(): String {
            val tempDirectory =
                sequenceOf("TMPDIR", "TMP", "TEMP", "TEMPDIR").firstNotNullOfOrNull {
                    getenv(it)?.toKString()
                } ?: "/tmp"
            val directory = mkdtemp("$tempDirectory/tmpdir.XXXXXX".cstr)?.toKString()
            return checkNotNull(directory)
        }

        actual suspend fun createDirectory(path: String): Boolean =
            withContext(Dispatchers.Default) {
                val result = mkdir(__path = path, __mode = 755)
                (result == 0)
            }

        actual suspend fun writeFile(path: String, text: String): Boolean =
            withContext(Dispatchers.Default) {
                memScoped {
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

        actual suspend fun move(source: String, destination: String): Boolean =
            withContext(Dispatchers.Default) {
                // destination が空のディレクトリであれば上書きされる
                val result = rename(
                    __old = source,
                    __new = destination
                )
                (result == 0)
            }


        actual suspend fun deleteRecursively(path: String): Boolean =
            withContext(Dispatchers.Default) {
                val ret = nftw(
                    path,
                    staticCFunction { pathName, _, _, _ ->
                        remove(checkNotNull(pathName).toKString())
                    },
                    64,
                    FTW_DEPTH or FTW_PHYS
                )
                (ret != -1)
            }
    }
}

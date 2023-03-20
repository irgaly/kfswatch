package io.github.irgaly.test.platform

import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toCValues
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.posix.FTW_DEPTH
import platform.posix.FTW_PHYS
import platform.posix.nftw
import platform.posix.remove
import platform.windows.CloseHandle
import platform.windows.CreateDirectoryW
import platform.windows.CreateFileW
import platform.windows.DWORDVar
import platform.windows.FALSE
import platform.windows.FILE_ATTRIBUTE_DIRECTORY
import platform.windows.FILE_ATTRIBUTE_NORMAL
import platform.windows.FILE_SHARE_DELETE
import platform.windows.FILE_SHARE_READ
import platform.windows.FILE_SHARE_WRITE
import platform.windows.GENERIC_WRITE
import platform.windows.GetFileAttributesW
import platform.windows.GetTempPathW
import platform.windows.INVALID_FILE_ATTRIBUTES
import platform.windows.MAX_PATH
import platform.windows.MOVEFILE_REPLACE_EXISTING
import platform.windows.MoveFileExW
import platform.windows.OPEN_ALWAYS
import platform.windows.RPC_WSTRVar
import platform.windows.RemoveDirectoryW
import platform.windows.RpcStringFreeW
import platform.windows.SetEndOfFile
import platform.windows.TCHARVar
import platform.windows.TRUE
import platform.windows.UUID
import platform.windows.UuidCreate
import platform.windows.UuidToStringW
import platform.windows.WriteFile

actual class Files {
    actual companion object {
        actual suspend fun createTemporaryDirectory(): String = withContext(Dispatchers.Default) {
            createTemporaryDirectorySync()
        }

        actual fun createTemporaryDirectorySync(): String {
            return memScoped {
                val tempPathBuffer = allocArray<TCHARVar>(MAX_PATH)
                val uuid = alloc<UUID>()
                val rpcString = alloc<RPC_WSTRVar>()
                val result = GetTempPathW(MAX_PATH, tempPathBuffer)
                if (result == 0U || MAX_PATH.toUInt() < result) {
                    error("GetTempPathW error")
                }
                val tempPath = tempPathBuffer.toKString()
                UuidCreate(Uuid = uuid.ptr)
                UuidToStringW(Uuid = uuid.ptr, StringUuid = rpcString.ptr)
                val uuidString = try {
                    rpcString.value!!.toKString()
                } finally {
                    RpcStringFreeW(String = rpcString.ptr)
                }
                val directory = "$tempPath$uuidString"
                CreateDirectoryW(
                    lpPathName = directory,
                    lpSecurityAttributes = null
                )
                directory
            }
        }

        actual suspend fun createDirectory(path: String): Boolean =
            withContext(Dispatchers.Default) {
                val result = CreateDirectoryW(
                    lpPathName = path,
                    lpSecurityAttributes = null
                )
                (result != FALSE)
            }

        actual suspend fun writeFile(path: String, text: String): Boolean =
            withContext(Dispatchers.Default) {
                memScoped {
                    val handle = CreateFileW(
                        lpFileName = path,
                        dwDesiredAccess = GENERIC_WRITE,
                        dwShareMode = (FILE_SHARE_DELETE or FILE_SHARE_READ or FILE_SHARE_WRITE).toUInt(),
                        lpSecurityAttributes = null,
                        dwCreationDisposition = OPEN_ALWAYS, // 既存ファイルを開く(内容はそのまま)、または新規作成する
                        dwFlagsAndAttributes = FILE_ATTRIBUTE_NORMAL,
                        hTemplateFile = null
                    )
                    val bytes = text.encodeToByteArray().toCValues()
                    val written = alloc<DWORDVar>()
                    WriteFile(
                        hFile = handle,
                        lpBuffer = bytes.ptr,
                        nNumberOfBytesToWrite = bytes.size.toUInt(),
                        lpNumberOfBytesWritten = written.ptr,
                        lpOverlapped = null
                    )
                    SetEndOfFile(hFile = handle)
                    CloseHandle(hObject = handle)
                    (written.value == bytes.size.toUInt())
                }
            }


        actual suspend fun move(source: String, destination: String): Boolean =
            withContext(Dispatchers.Default) {
                val isDirectory = run {
                    val attributes = GetFileAttributesW(destination)
                    if (attributes != INVALID_FILE_ATTRIBUTES) {
                        ((attributes.toInt() and FILE_ATTRIBUTE_DIRECTORY) != 0)
                    } else false
                }
                if (isDirectory) {
                    // destination が空ディレクトリであれば削除できる
                    RemoveDirectoryW(destination)
                }
                // https://learn.microsoft.com/ja-jp/windows/win32/api/winbase/nf-winbase-movefileexw
                val result = MoveFileExW(
                    lpExistingFileName = source,
                    lpNewFileName = destination,
                    // destination がファイルであれば上書きする
                    // ディレクトリの場合はエラー
                    dwFlags = MOVEFILE_REPLACE_EXISTING
                )
                (result != FALSE)
            }


        actual suspend fun deleteRecursively(path: String): Boolean =
            withContext(Dispatchers.Default) {
                val result = nftw(
                    /* __dir = */ path,
                    /* __func = */ staticCFunction { pathName, _, _, _ ->
                        val path = pathName!!.toKString()
                        val deleted = RemoveDirectoryW(
                            lpPathName = path
                        )
                        if (deleted == TRUE) {
                            TRUE
                        } else {
                            remove(path)
                        }
                    },
                    /* __descriptors = */ 64,
                    /* __flag = */ FTW_DEPTH or FTW_PHYS
                )
                (result != -1)
            }
    }
}

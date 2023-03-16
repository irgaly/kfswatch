package io.github.irgaly.test.platform

private val fs: dynamic get() = js("require('fs')")
private val path: dynamic get() = js("require('path')")
private val os: dynamic get() = js("require('os')")

actual class Files {
    actual companion object {
        actual fun createTemporaryDirectory(): String {
            return if (isBrowser()) {
                "js-browser-dummy-temporary-directory"
            } else {
                fs.mkdtempSync(path.join(os.tmpdir(), "")).unsafeCast<String>()
            }
        }

        actual fun createDirectory(path: String): Boolean {
            return try {
                fs.mkdirSync(path)
                true
            } catch(_: Exception) {
                false
            }
        }

        actual fun writeFile(path: String, text: String): Boolean {
            return try {
                fs.writeFileSync(path, text)
                true
            } catch(_: Exception) {
                false
            }
        }

        actual fun deleteRecursively(path: String): Boolean {
            if (isNodejs()) {
                fs.rmSync(path, js("{recursive: true, force: true}"))
            }
            return true
        }
    }
}


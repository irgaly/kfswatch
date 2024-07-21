package io.github.irgaly.kfswatch.internal.platform

private fun typeofWindow(): String = js("typeof window")

@Suppress("unused")
internal fun isBrowser(): Boolean {
    return (typeofWindow() != "undefined")
}

@Suppress("unused")
internal fun isNodejs(): Boolean {
    return (typeofWindow() == "undefined")
}

private fun getPlatform(): String = js("require('os').platform()")

@Suppress("unused")
internal fun isLinux(): Boolean {
    return if (isBrowser()) {
        false
    } else {
        (getPlatform() == "linux")
    }
}

@Suppress("unused")
internal fun isMacos(): Boolean {
    return if (isBrowser()) {
        false
    } else {
        (getPlatform() == "darwin")
    }
}

@Suppress("unused")
internal fun isWindows(): Boolean {
    return if (isBrowser()) {
        false
    } else {
        (getPlatform() == "win32")
    }
}

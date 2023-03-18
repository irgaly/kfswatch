package io.github.irgaly.kfswatch.internal.platform

@Suppress("unused")
internal fun isBrowser(): Boolean {
    return (js("typeof window") != "undefined")
}

@Suppress("unused")
internal fun isNodejs(): Boolean {
    return (js("typeof window") == "undefined")
}

@Suppress("unused")
internal fun isMacos(): Boolean {
    return (js("require('os').platform()") == "darwin")
}

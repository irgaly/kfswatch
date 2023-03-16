package io.github.irgaly.test.platform

@Suppress("unused")
fun isBrowser(): Boolean {
    return (js("typeof window") != "undefined")
}

@Suppress("unused")
fun isNodejs(): Boolean {
    return (js("typeof window") == "undefined")
}

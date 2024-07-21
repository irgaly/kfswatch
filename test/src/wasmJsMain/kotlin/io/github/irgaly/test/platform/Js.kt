package io.github.irgaly.test.platform

private fun typeofWindow(): String = js("typeof window")

@Suppress("unused")
fun isBrowser(): Boolean {
    return (typeofWindow() != "undefined")
}

@Suppress("unused")
fun isNodejs(): Boolean {
    return (typeofWindow() == "undefined")
}

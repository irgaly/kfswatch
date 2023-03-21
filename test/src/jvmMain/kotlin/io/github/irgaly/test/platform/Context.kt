package io.github.irgaly.test.platform

actual fun getContext(): Context {
    return object: Context {}
}

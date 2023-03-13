package io.github.irgaly.kfswatch.internal.platform

internal interface Logger {
    fun debug(message: () -> String)
    fun error(message: () -> String)
}

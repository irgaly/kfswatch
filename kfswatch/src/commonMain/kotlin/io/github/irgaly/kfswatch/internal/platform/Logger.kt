package io.github.irgaly.kfswatch.internal.platform

internal interface Logger {
    suspend fun debug(message: String)
    suspend fun error(message: String)
}

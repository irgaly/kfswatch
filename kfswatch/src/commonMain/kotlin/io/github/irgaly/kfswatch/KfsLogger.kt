package io.github.irgaly.kfswatch

interface KfsLogger {
    suspend fun debug(message: String)
    suspend fun error(message: String)
}

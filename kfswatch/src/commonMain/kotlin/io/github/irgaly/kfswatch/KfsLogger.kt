package io.github.irgaly.kfswatch

interface KfsLogger {
    fun debug(message: String)
    fun error(message: String)
}

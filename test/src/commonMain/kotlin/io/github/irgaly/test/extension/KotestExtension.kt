package io.github.irgaly.test.extension

import io.github.irgaly.test.platform.Files
import io.kotest.common.runBlocking
import io.kotest.core.TestConfiguration

/**
 * https://github.com/kotest/kotest/blob/2ba0cf84d0eace0f652258b4b0cae29e1492f125/kotest-framework/kotest-framework-engine/src/jvmMain/kotlin/io/kotest/engine/spec/tempdir.kt
 * common tempdir()
 */
fun TestConfiguration.tempdir(clearAfterSpec: Boolean = true): String {
    val directory = runBlocking { Files.createTemporaryDirectory() }
    if (clearAfterSpec) {
        afterSpec {
            if (!Files.deleteRecursively(directory)) {
                throw Exception("cannot delete temporary directory: $directory")
            }
        }
    }
    return directory
}

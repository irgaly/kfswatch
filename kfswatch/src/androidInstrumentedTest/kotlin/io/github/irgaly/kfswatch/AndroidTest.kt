package io.github.irgaly.kfswatch

import io.kotest.engine.TestEngineLauncher
import io.kotest.engine.listener.CollectingTestEngineListener
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll

class AndroidTest {
    @Test
    fun commonTest() = runTest {
        val listener = CollectingTestEngineListener()
        TestEngineLauncher(listener).withClasses(CommonSpec::class).launch()
        listener.tests.map { entry ->
            {
                val testCase = entry.key
                val descriptor = testCase.descriptor.chain().joinToString(" > ") {
                    it.id.value
                }
                assertFalse(entry.value.isErrorOrFailure) { "$descriptor : ${entry.value}" }
            }
        }.let {
            assertAll(it)
        }
    }
}

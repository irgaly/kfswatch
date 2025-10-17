package io.github.irgaly.kfswatch

import io.kotest.common.KotestInternal
import io.kotest.engine.TestEngineLauncher
import io.kotest.engine.listener.CollectingTestEngineListener
import io.kotest.engine.test.TestResult
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll

class AndroidTest {
    @OptIn(KotestInternal::class)
    @Test
    fun commonTest() {
        val listener = CollectingTestEngineListener()
        TestEngineLauncher()
            .withListener(listener)
            .withClasses(KfswatchSpec::class).launch()
        listener.tests.map { entry ->
            {
                val testCase = entry.key
                val descriptor = testCase.descriptor.path().value
                val cause = when (val value = entry.value) {
                    is TestResult.Error -> value.cause
                    is TestResult.Failure -> value.cause
                    else -> null
                }
                assertFalse(entry.value.isErrorOrFailure) {
                    """$descriptor
                    |${cause?.stackTraceToString()}""".trimMargin()
                }
            }
        }.let {
            assertAll(it)
        }
        println("Total ${listener.tests.size}, Failure ${listener.tests.count { it.value.isErrorOrFailure }}")
    }
}

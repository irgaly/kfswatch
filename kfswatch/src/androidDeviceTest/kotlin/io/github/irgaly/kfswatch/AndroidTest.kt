package io.github.irgaly.kfswatch

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.common.KotestInternal
import io.kotest.core.spec.SpecRef
import io.kotest.engine.TestEngineLauncher
import io.kotest.engine.listener.CollectingTestEngineListener
import io.kotest.engine.test.TestResult
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.Test

class AndroidTest {
    @OptIn(KotestInternal::class)
    @Test
    fun commonTest() = runTest {
        val listener = CollectingTestEngineListener()
        TestEngineLauncher()
            .withListener(listener)
            .withSpecRefs(SpecRef.Reference(KfswatchSpec::class))
            .execute()
        assertSoftly {
            for (entry in listener.tests) {
                val testCase = entry.key
                val descriptor = testCase.descriptor.path().value
                val cause = when (val value = entry.value) {
                    is TestResult.Error -> value.cause
                    is TestResult.Failure -> value.cause
                    else -> null
                }
                withClue({
                    """$descriptor
                    |${cause?.stackTraceToString()}""".trimMargin()
                }) {
                    entry.value.isErrorOrFailure shouldBe false
                }
            }
        }
        println("Total ${listener.tests.size}, Failure ${listener.tests.count { it.value.isErrorOrFailure }}")
    }
}

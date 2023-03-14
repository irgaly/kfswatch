package io.github.irgaly.kfswatch

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry.getInstrumentation
import app.cash.turbine.test
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
class KfsDirectoryWatcherTest {
    companion object {
        private val context get() = getInstrumentation().targetContext
        private val testDirectory get() = context.filesDir.resolve("test")
        private val logger
            get() = object : KfsLogger {
                override suspend fun debug(message: String) {
                    Log.d("KfsLogger", message)
                }

                override suspend fun error(message: String) {
                    Log.e("KfsLogger", message)
                }
            }

        @BeforeAll
        @JvmStatic
        fun setup() {
            testDirectory.deleteRecursively()
            testDirectory.mkdir()
        }

        @AfterAll
        @JvmStatic
        fun teardown() {
            testDirectory.deleteRecursively()
        }
    }

    @Test
    fun test() = runTest {
        val dir = testDirectory.resolve("test1").also {
            it.mkdir()
        }
        val watcher = KfsDirectoryWatcher(
            this,
            logger = logger
        )
        watcher.onEventFlow.test {
            watcher.add(dir.path)
            dir.resolve("child1").mkdir()
            awaitItem() should {
                it.event shouldBe KfsEvent.Create
                it.path shouldBe "child1"
            }
        }
        watcher.close()
    }

    @Test
    fun test2() {
        0 shouldBe 1
    }
}

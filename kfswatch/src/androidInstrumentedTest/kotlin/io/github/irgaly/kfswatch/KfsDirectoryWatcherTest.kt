package io.github.irgaly.kfswatch

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry.getInstrumentation
import io.github.irgaly.kfswatch.internal.platform.FileWatcher
import io.github.irgaly.kfswatch.internal.platform.Logger
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
class KfsDirectoryWatcherTest {
    companion object {
        private val context get() = getInstrumentation().targetContext
        private val testDirectory get() = context.filesDir.resolve("test")

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
        val watcher = FileWatcher(
            onEvent = { target, path, event ->
                Log.d("event", "event: $target/$path: $event")
            },
            onStart = {
                Log.d("onStart", "onStart: $it")
            },
            onStop = {
                Log.d("onStop", "onStop: $it")
            },
            onError = { target, message ->
                Log.e("error", "error: $target : $message")
            },
            logger = object : Logger {
                override fun debug(message: () -> String) {
                    Log.d("logger", message())
                }

                override fun error(message: () -> String) {
                    Log.e("logger", message())
                }
            }
        )
        watcher.start(listOf(dir.path))
        delay(100.milliseconds)
        dir.resolve("child1").mkdir()
        delay(100.milliseconds)
        watcher.close()
        delay(100.milliseconds)
    }

    @Test
    fun test2() {
        0 shouldBe 1
    }
}

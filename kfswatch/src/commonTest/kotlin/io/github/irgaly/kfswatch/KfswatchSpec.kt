package io.github.irgaly.kfswatch

import app.cash.turbine.test
import app.cash.turbine.testIn
import io.github.irgaly.kfswatch.internal.platform.Files
import io.github.irgaly.test.DescribeFunSpec
import io.github.irgaly.test.extension.tempdir
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
class KfswatchSpec : DescribeFunSpec({
    val tempDirectory = tempdir(true)
    val logger = object : KfsLogger {
        override suspend fun debug(message: String) {
            println("debug: $message")
        }

        override suspend fun error(message: String) {
            println("error: $message")
        }
    }
    describe("基本機能") {
        it("directory 作成を検出できる") {
            val directory = "$tempDirectory/test1"
            Files.mkdirs(directory)
            val watcher = KfsDirectoryWatcher(
                this,
                logger = logger
            )
            val errors = watcher.onErrorFlow.testIn(this)
            watcher.onEventFlow.test {
                watcher.add(directory)
                Files.mkdirs("$directory/child1")
                awaitItem() should {
                    it.event shouldBe KfsEvent.Create
                    it.path shouldBe "child1"
                }
            }
            errors.ensureAllEventsConsumed()
            errors.cancel()
            watcher.close()
        }
    }
})

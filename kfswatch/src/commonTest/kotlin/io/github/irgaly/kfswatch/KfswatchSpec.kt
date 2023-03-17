package io.github.irgaly.kfswatch

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import app.cash.turbine.testIn
import io.github.irgaly.test.DescribeFunSpec
import io.github.irgaly.test.extension.tempdir
import io.github.irgaly.test.platform.Files
import io.kotest.core.test.TestScope
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

    fun TestScope.createWatcher(): KfsDirectoryWatcher {
        return KfsDirectoryWatcher(this, logger = logger)
    }

    suspend fun mkdirs(path: String) {
        io.github.irgaly.kfswatch.internal.platform.Files.mkdirs(path)
    }

    suspend fun ReceiveTurbine<KfsDirectoryWatcherEvent>.awaitEvent(
        event: KfsEvent,
        path: String
    ) {
        awaitItem() should {
            it.event shouldBe event
            it.path shouldBe path
        }
    }

    describe("基本機能") {
        it("directory, file の Create, Delete, Rename を検出できる") {
            val directory = "$tempDirectory/test1".also { mkdirs(it) }
            val watcher = createWatcher()
            val errors = watcher.onErrorFlow.testIn(this)
            watcher.onEventFlow.test {
                watcher.add(directory)
                mkdirs("$directory/child1")
                awaitEvent(KfsEvent.Create, "child1")
                Files.writeFile("$directory/child2", "test")
                awaitEvent(KfsEvent.Create, "child2")
                awaitEvent(KfsEvent.Modify, "child2")
                Files.writeFile("$directory/child3", "")
                awaitEvent(KfsEvent.Create, "child3")
                Files.move("$directory/child2", "$directory/child3")
                awaitEvent(KfsEvent.Delete, "child2")
                awaitEvent(KfsEvent.Create, "child3")
            }
            errors.ensureAllEventsConsumed()
            errors.cancel()
            watcher.close()
        }
    }
})

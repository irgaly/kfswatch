package io.github.irgaly.kfswatch

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import app.cash.turbine.testIn
import io.github.irgaly.test.DescribeFunSpec
import io.github.irgaly.test.extension.tempdir
import io.github.irgaly.test.platform.Files
import io.kotest.assertions.fail
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
        //@OptIn(DelicateCoroutinesApi::class)
        return KfsDirectoryWatcher(
            scope = this,
            //scope = CoroutineScope(GlobalScope.coroutineContext + Job()), // テストが失敗するときはエラーログ出力にこちらを使う
            logger = logger
        )
    }

    suspend fun mkdirs(path: String) {
        io.github.irgaly.kfswatch.internal.platform.Files.mkdirs(path)
    }

    suspend fun ReceiveTurbine<KfsDirectoryWatcherEvent>.awaitEvent(
        event: KfsEvent,
        path: String,
        targetDirectory: String? = null
    ) {
        awaitItem() should {
            it.event shouldBe event
            it.path shouldBe path
            if (targetDirectory != null) {
                it.targetDirectory shouldBe targetDirectory
            }
        }
    }

    suspend fun ReceiveTurbine<KfsDirectoryWatcherEvent>.awaitEvents(vararg events: Event) {
        val list = events.toMutableList()
        (0..list.lastIndex).forEach {
            val item = awaitItem()
            val index = list.indexOfFirst { event ->
                (event.event == item.event) &&
                        (event.path == item.path) &&
                        (event.targetDirectory?.let { it == item.targetDirectory } ?: true)
            }
            if (index < 0) {
                fail("$item is not expected in: ${events.joinToString(",")}")
            } else {
                list.removeAt(index)
            }
        }
    }

    describe("基本機能") {
        it("directory, file の Create, Delete, Modify を検出できる") {
            val directory = "$tempDirectory/simple".also { mkdirs(it) }
            val watcher = createWatcher()
            val errors = watcher.onErrorFlow.testIn(this)
            watcher.onEventFlow.test {
                watcher.add(directory)
                mkdirs("$directory/child1")
                awaitEvent(KfsEvent.Create, "child1")
                Files.writeFile("$directory/child2", "test")
                awaitEvents(
                    Event(KfsEvent.Create, "child2"),
                    Event(KfsEvent.Modify, "child2")
                )
                Files.writeFile("$directory/child2", "test2")
                awaitEvent(KfsEvent.Modify, "child2")
                Files.writeFile("$directory/child3", "")
                awaitEvent(KfsEvent.Create, "child3")
                Files.deleteRecursively("$directory/child1")
                awaitEvent(KfsEvent.Delete, "child1")
                Files.deleteRecursively("$directory/child2")
                awaitEvent(KfsEvent.Delete, "child2")
            }
            errors.ensureAllEventsConsumed()
            errors.cancel()
            watcher.close()
        }
        it("add, remove") {
            val directory = "$tempDirectory/add_remove".also { mkdirs(it) }
            val watcher = createWatcher()
            val errors = watcher.onErrorFlow.testIn(this)
            val starts = watcher.onStartFlow.testIn(this)
            val stops = watcher.onStopFlow.testIn(this)
            watcher.onEventFlow.test {
                watcher.add(directory)
                starts.awaitItem() shouldBe directory
                mkdirs("$directory/child")
                awaitEvent(KfsEvent.Create, "child")
                watcher.remove(directory)
                stops.awaitItem() shouldBe directory
                mkdirs("$directory/child2")
                // no events
            }
            stops.ensureAllEventsConsumed()
            stops.cancel()
            starts.ensureAllEventsConsumed()
            starts.cancel()
            errors.ensureAllEventsConsumed()
            errors.cancel()
            watcher.close()
        }
    }
    describe("複数監視") {
        it("2つの directory を監視できる") {
            val directory1 = "$tempDirectory/many2/target1".also { mkdirs(it) }
            val directory2 = "$tempDirectory/many2/target2".also { mkdirs(it) }
            val watcher = createWatcher()
            val errors = watcher.onErrorFlow.testIn(this)
            watcher.onEventFlow.test {
                watcher.add(directory1, directory2)
                mkdirs("$directory1/child1")
                mkdirs("$directory2/child2")
                awaitEvent(KfsEvent.Create, "child1", directory1)
                awaitEvent(KfsEvent.Create, "child2", directory2)
            }
            errors.ensureAllEventsConsumed()
            errors.cancel()
            watcher.close()
        }
        it("同じ directory を複数 add しても監視はひとつ") {
            val directory = "$tempDirectory/many_same/target1".also { mkdirs(it) }
            val watcher = createWatcher()
            val errors = watcher.onErrorFlow.testIn(this)
            val starts = watcher.onStartFlow.testIn(this)
            val stops = watcher.onStopFlow.testIn(this)
            watcher.onEventFlow.test {
                watcher.add(directory)
                watcher.add(directory)
                starts.awaitItem() shouldBe directory
                mkdirs("$directory/child")
                awaitEvent(KfsEvent.Create, "child")
                watcher.remove(directory)
                stops.awaitItem() shouldBe directory
            }
            stops.ensureAllEventsConsumed()
            stops.cancel()
            starts.ensureAllEventsConsumed()
            starts.cancel()
            errors.ensureAllEventsConsumed()
            errors.cancel()
            watcher.close()
        }
        it("監視対象は 64 個まで可能") {
            val directory = "$tempDirectory/many_64".also { mkdirs(it) }
            (1..65).forEach {
                mkdirs("$directory/directory$it")
            }
            val watcher = createWatcher()
            val errors = watcher.onErrorFlow.testIn(this)
            val starts = watcher.onStartFlow.testIn(this)
            val stops = watcher.onStopFlow.testIn(this)
            watcher.onEventFlow.test {
                (1..64).forEach {
                    val target = "$directory/directory$it"
                    watcher.add(target)
                    starts.awaitItem() shouldBe target
                    Files.writeFile("$target/file", "")
                    awaitEvent(KfsEvent.Create, "file", target)
                    watcher.watchingDirectories.size shouldBe it
                }
                val target = "$directory/directory65"
                watcher.add(target)
                errors.awaitItem().targetDirectory shouldBe target
                watcher.watchingDirectories.size shouldBe 64
                (1..64).forEach {
                    val target = "$directory/directory$it"
                    watcher.remove(target)
                    stops.awaitItem() shouldBe target
                    watcher.watchingDirectories.size shouldBe (64 - it)
                }
            }
            stops.ensureAllEventsConsumed()
            stops.cancel()
            starts.ensureAllEventsConsumed()
            starts.cancel()
            errors.ensureAllEventsConsumed()
            errors.cancel()
            watcher.close()
        }
    }
    describe("move 操作") {
        it("同一ディレクトリ内 move") {
            val directory = "$tempDirectory/move_inside".also { mkdirs(it) }
            val file = "$directory/file".also { Files.writeFile(it, "") }
            val watcher = createWatcher()
            val errors = watcher.onErrorFlow.testIn(this)
            watcher.onEventFlow.test {
                watcher.add(directory)
                Files.move(file, "$directory/file2")
                awaitEvents(
                    Event(KfsEvent.Delete, "file"),
                    Event(KfsEvent.Create, "file2")
                )
            }
            errors.ensureAllEventsConsumed()
            errors.cancel()
            watcher.close()
        }
        it("ディレクトリ外への move") {
            val directory = "$tempDirectory/move_out".also { mkdirs(it) }
            val target = "$directory/target".also { mkdirs(it) }
            val file = "$target/file".also { Files.writeFile(it, "") }
            val watcher = createWatcher()
            val errors = watcher.onErrorFlow.testIn(this)
            watcher.onEventFlow.test {
                watcher.add(target)
                Files.move(file, "$directory/file2")
                awaitEvent(KfsEvent.Delete, "file")
            }
            errors.ensureAllEventsConsumed()
            errors.cancel()
            watcher.close()
        }
        it("ディレクトリ内への move") {
            val directory = "$tempDirectory/move_in".also { mkdirs(it) }
            val target = "$directory/target".also { mkdirs(it) }
            val file = "$directory/file".also { Files.writeFile(it, "") }
            val watcher = createWatcher()
            val errors = watcher.onErrorFlow.testIn(this)
            watcher.onEventFlow.test {
                watcher.add(target)
                Files.move(file, "$target/file2")
                awaitEvent(KfsEvent.Create, "file2")
            }
            errors.ensureAllEventsConsumed()
            errors.cancel()
            watcher.close()
        }
    }
    describe("上書き移動") {
        it("ファイルの上書き移動") {
            val directory = "$tempDirectory/overwrite_file".also { mkdirs(it) }
            val file = "$directory/file".also { Files.writeFile(it, "file") }
            val file2 = "$directory/file2".also { Files.writeFile(it, "file2") }
            val watcher = createWatcher()
            val errors = watcher.onErrorFlow.testIn(this)
            watcher.onEventFlow.test {
                watcher.add(directory)
                val result = Files.move(file, file2)
                result shouldBe true
                awaitEvents(
                    Event(KfsEvent.Delete, "file"),
                    Event(KfsEvent.Create, "file2")
                )
            }
            errors.ensureAllEventsConsumed()
            errors.cancel()
            watcher.close()
        }
        it("directory の上書き移動") {
            val directory = "$tempDirectory/overwrite_directory".also { mkdirs(it) }
            val directory1 = "$directory/directory1".also { mkdirs(it) }
            val directory2 = "$directory/directory2".also { mkdirs(it) }
            val watcher = createWatcher()
            val errors = watcher.onErrorFlow.testIn(this)
            watcher.onEventFlow.test {
                watcher.add(directory)
                Files.move(directory1, directory2)
                awaitEvents(
                    Event(KfsEvent.Delete, "directory1"),
                    Event(KfsEvent.Create, "directory2")
                )
            }
            errors.ensureAllEventsConsumed()
            errors.cancel()
            watcher.close()
        }
    }
    describe("検出しないもの") {
        it("監視対象の directory の移動は検出されない") {
            val directory = "$tempDirectory/directory_move".also { mkdirs(it) }
            val target = "$directory/target".also { mkdirs(it) }
            val watcher = createWatcher()
            val errors = watcher.onErrorFlow.testIn(this)
            val stops = watcher.onStopFlow.testIn(this)
            watcher.onEventFlow.test {
                watcher.add(target)
                Files.move(target, "$directory/moved")
            }
            stops.ensureAllEventsConsumed()
            stops.cancel()
            errors.ensureAllEventsConsumed()
            errors.cancel()
            watcher.close()
        }
        it("監視対象の directory の削除で監視停止") {
            val directory = "$tempDirectory/directory_delete".also { mkdirs(it) }
            val watcher = createWatcher()
            val errors = watcher.onErrorFlow.testIn(this)
            val stops = watcher.onStopFlow.testIn(this)
            watcher.onEventFlow.test {
                watcher.add(directory)
                Files.deleteRecursively(directory)
                stops.awaitItem() shouldBe directory
            }
            stops.ensureAllEventsConsumed()
            stops.cancel()
            errors.ensureAllEventsConsumed()
            errors.cancel()
            watcher.close()
        }
        it("監視対象の親ディレクトリの移動は検出しない") {
            val directory = "$tempDirectory/target_parent".also { mkdirs(it) }
            val parent = "$directory/parent".also { mkdirs(it) }
            val target = "$parent/target".also { mkdirs(it) }
            val watcher = createWatcher()
            val errors = watcher.onErrorFlow.testIn(this)
            watcher.onEventFlow.test {
                watcher.add(target)
                Files.move(parent, "$directory/parent2")
                Files.writeFile("$directory/parent2/target/child", "")
                awaitEvent(KfsEvent.Create, "child")
            }
            errors.ensureAllEventsConsumed()
            errors.cancel()
            watcher.close()
        }
        it("子ディレクトリ内の変化は検出しない") {
            val directory = "$tempDirectory/directory_child".also { mkdirs(it) }
            val child = "$directory/child".also { mkdirs(it) }
            val watcher = createWatcher()
            val errors = watcher.onErrorFlow.testIn(this)
            watcher.onEventFlow.test {
                watcher.add(directory)
                mkdirs("$child/child2")
            }
            errors.ensureAllEventsConsumed()
            errors.cancel()
            watcher.close()
        }
    }
})

private data class Event(
    val event: KfsEvent,
    val path: String,
    val targetDirectory: String? = null
)

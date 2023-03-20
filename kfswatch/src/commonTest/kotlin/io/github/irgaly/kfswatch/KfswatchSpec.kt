package io.github.irgaly.kfswatch

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import app.cash.turbine.testIn
import io.github.irgaly.kfswatch.internal.platform.Platform
import io.github.irgaly.test.DescribeFunSpec
import io.github.irgaly.test.extension.tempdir
import io.github.irgaly.test.platform.Files
import io.kotest.assertions.fail
import io.kotest.core.test.TestScope
import io.kotest.matchers.collections.beEmpty
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
class KfswatchSpec : DescribeFunSpec({
    val tempDirectory = tempdir(true)
    val logger = object : KfsLogger {
        override fun debug(message: String) {
            println("debug: $message")
        }

        override fun error(message: String) {
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

    suspend fun KfsDirectoryWatcher.addWait(vararg targets: String) {
        onStartFlow.test {
            add(*targets)
            val set = targets.toMutableSet()
            repeat(set.size) {
                val item = awaitItem()
                val removed = set.remove(item)
                if (!removed) {
                    fail("$item is not expected in: ${set.joinToString(",")}")
                }
            }
        }
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
        repeat(list.size) {
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
            val directory = "$tempDirectory/basic".also { mkdirs(it) }
            val watcher = createWatcher()
            val errors = watcher.onErrorFlow.testIn(this)
            watcher.onEventFlow.distinctUntilChanged().test(timeout = 5.seconds) {
                // ファイル内容書き換えでは同じ内容の Modify が複数流れることがあるので distinct する
                watcher.addWait(directory)
                mkdirs("$directory/child1")
                awaitEvent(KfsEvent.Create, "child1")
                Files.writeFile("$directory/child2", "test")
                if (Platform.isJvmMacos
                    || Platform.isNodejsMacos
                    || Platform.isMacos
                    || Platform.isIos
                ) {
                    // JVM on macOS はポーリング監視実装のため
                    // 新規作成では Modify イベントは発生しない
                    // Nodejs on macOS も Modify は発生しない
                    // macOS, iOS kqueue も Modify は発生しない
                    awaitEvents(
                        Event(KfsEvent.Create, "child2")
                    )
                } else {
                    awaitEvents(
                        Event(KfsEvent.Create, "child2"),
                        Event(KfsEvent.Modify, "child2")
                    )
                }
                Files.writeFile("$directory/child3", "")
                awaitEvent(KfsEvent.Create, "child3")
                if (Platform.isWindows) {
                    // Windows では空のファイル作成でも Modify イベントが発生する
                    awaitEvent(KfsEvent.Modify, "child3")
                }
                Files.writeFile("$directory/child2", "test2")
                awaitEvent(KfsEvent.Modify, "child2")
                Files.deleteRecursively("$directory/child1")
                awaitEvent(KfsEvent.Delete, "child1")
                Files.deleteRecursively("$directory/child2")
                if (Platform.isJvmWindows) {
                    // JVM on Windows ではファイル削除で Modify, Delete が発生する
                    awaitEvents(
                        Event(KfsEvent.Modify, "child2"),
                        Event(KfsEvent.Delete, "child2")
                    )
                } else {
                    awaitEvent(KfsEvent.Delete, "child2")
                }
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
            watcher.onEventFlow.test(timeout = 5.seconds) {
                watcher.addWait(directory)
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
            watcher.onEventFlow.test(timeout = 5.seconds) {
                watcher.addWait(directory1, directory2)
                mkdirs("$directory1/child1")
                awaitEvent(KfsEvent.Create, "child1", directory1)
                mkdirs("$directory2/child2")
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
            watcher.onEventFlow.test(timeout = 5.seconds) {
                watcher.addWait(directory)
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
        it("監視対象は 63 個まで可能") {
            val directory = "$tempDirectory/many_63".also { mkdirs(it) }
            (1..64).forEach {
                mkdirs("$directory/directory$it")
            }
            val watcher = createWatcher()
            val errors = watcher.onErrorFlow.testIn(this)
            val starts = watcher.onStartFlow.testIn(this)
            val stops = watcher.onStopFlow.testIn(this)
            watcher.onEventFlow.test(timeout = 5.seconds) {
                (1..63).forEach {
                    val target = "$directory/directory$it"
                    watcher.addWait(target)
                    starts.awaitItem() shouldBe target
                    if (Platform.isNodejs) {
                        // Nodejs で監視開始直後のイベントを受け取れないことがあるため delay
                        delay(10.milliseconds)
                    }
                    Files.writeFile("$target/file", "")
                    watcher.watchingDirectories.size shouldBe it
                }
                // JVM on macOS はイベント通知が遅いので、まとめてイベントをチェックする
                if (Platform.isWindows) {
                    // Windows はファイルの新規作成で Create - Modify が発生する
                    awaitEvents(
                        *(1..63).flatMap {
                            listOf(
                                Event(KfsEvent.Create, "file", "$directory/directory$it"),
                                Event(KfsEvent.Modify, "file", "$directory/directory$it"),
                            )
                        }.toTypedArray()
                    )
                } else {
                    awaitEvents(
                        *(1..63).map {
                            Event(KfsEvent.Create, "file", "$directory/directory$it")
                        }.toTypedArray()
                    )
                }
                val target64 = "$directory/directory64"
                watcher.add(target64)
                errors.awaitItem().targetDirectory shouldBe target64
                watcher.watchingDirectories.size shouldBe 63
                (1..63).forEach {
                    val target = "$directory/directory$it"
                    watcher.remove(target)
                    stops.awaitItem() shouldBe target
                    watcher.watchingDirectories.size shouldBe (63 - it)
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
            watcher.onEventFlow.test(timeout = 5.seconds) {
                watcher.addWait(directory)
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
            watcher.onEventFlow.test(timeout = 5.seconds) {
                watcher.addWait(target)
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
            watcher.onEventFlow.test(timeout = 5.seconds) {
                watcher.addWait(target)
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
            val file1 = "$directory/file1".also { Files.writeFile(it, "file1") }
            val file2 = "$directory/file2".also { Files.writeFile(it, "file2") }
            val watcher = createWatcher()
            val errors = watcher.onErrorFlow.testIn(this)
            watcher.onEventFlow.test(timeout = 5.seconds) {
                watcher.addWait(directory)
                val result = Files.move(file1, file2)
                result shouldBe true
                when {
                    (Platform.isJvmMacos || Platform.isNodejs) -> {
                        // JVM on macOS では上書き対象は Modify で通知される
                        // Nodejs も上書きは rename - Modify で通知される
                        awaitEvents(
                            Event(KfsEvent.Delete, "file1"),
                            Event(KfsEvent.Modify, "file2")
                        )
                    }

                    (Platform.isJvmLinux
                            || Platform.isJvmWindows
                            || Platform.isMacos
                            || Platform.isIos
                            || Platform.isWindows) -> {
                        // JVM on Linux, JVM on Windows, macOS, iOS, Windows
                        // では file2 の Delete が発生する
                        awaitEvents(
                            Event(KfsEvent.Delete, "file1"),
                            Event(KfsEvent.Delete, "file2"),
                            Event(KfsEvent.Create, "file2")
                        )
                    }

                    else -> {
                        awaitEvents(
                            Event(KfsEvent.Delete, "file1"),
                            Event(KfsEvent.Create, "file2")
                        )
                    }
                }
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
            watcher.onEventFlow.test(timeout = 5.seconds) {
                watcher.addWait(directory)
                Files.move(directory1, directory2)
                when {
                    Platform.isJvmMacos -> {
                        // JVM on macOS では
                        // directory が置き換えられたときに以下のどちらかとなる
                        // * Modify イベントが発生する
                        // * イベントが検出されない
                        // directory2 Modify イベントが発生しない可能性に対応する
                        var received: KfsDirectoryWatcherEvent? = null
                        repeat(2) {
                            if (received == null) {
                                val item = awaitItem()
                                if (item.event == KfsEvent.Delete) {
                                    received = item
                                }
                            }
                        }
                        received.should {
                            it?.event shouldBe KfsEvent.Delete
                            it?.path shouldBe "directory1"
                        }
                        cancelAndIgnoreRemainingEvents()
                    }

                    (Platform.isJvmLinux
                            || Platform.isJvmWindows
                            || Platform.isNodejsWindows
                            || Platform.isMacos
                            || Platform.isIos
                            || Platform.isWindows) -> {
                        // JVM on Linux, JVM on Windows, Nodejs on Windows
                        // macOS, iOS, Windows
                        // では directory2 の Delete が発生する
                        awaitEvents(
                            Event(KfsEvent.Delete, "directory1"),
                            Event(KfsEvent.Delete, "directory2"),
                            Event(KfsEvent.Create, "directory2")
                        )
                    }

                    Platform.isNodejsLinux -> {
                        // Nodejs on Linux では directory2 を削除してから rename する
                        // 上書きされる directory2 は Delete - Create または Modify - Modify
                        // で通知される
                        val events = listOf(
                            awaitItem(),
                            awaitItem(),
                            awaitItem()
                        )
                        var isModify = false
                        events.forEach { event ->
                            when (event.path) {
                                "directory1" -> {
                                    event.event shouldBe KfsEvent.Delete
                                }

                                "directory2" -> {
                                    if (event.event == KfsEvent.Modify) {
                                        isModify = true
                                    }
                                    if (isModify) {
                                        // Modify のときは Modify -> Modify と発生する
                                        event.event shouldBe KfsEvent.Modify
                                    } else {
                                        // Delete のときは Delete -> Create と発生する
                                        event.event shouldBeIn listOf(
                                            KfsEvent.Delete,
                                            KfsEvent.Create
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Platform.isNodejsMacos -> {
                        // Nodejs on macOS では directory2 を削除してから
                        // rename する
                        // 上書きは Modify として検出される
                        awaitEvents(
                            Event(KfsEvent.Modify, "directory2"),
                            Event(KfsEvent.Delete, "directory1"),
                            Event(KfsEvent.Modify, "directory2")
                        )
                    }

                    else -> {
                        awaitEvents(
                            Event(KfsEvent.Delete, "directory1"),
                            Event(KfsEvent.Create, "directory2")
                        )
                    }
                }
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
            watcher.onEventFlow.test(timeout = 5.seconds) {
                watcher.addWait(target)
                Files.move(target, "$directory/moved")
                if (Platform.isNodejs) {
                    // Nodejs では監視対象の移動または削除で監視が停止される
                    stops.awaitItem() shouldBe target
                }
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
            watcher.onEventFlow.test(timeout = 5.seconds) {
                watcher.addWait(directory)
                Files.deleteRecursively(directory)
                if (Platform.isNodejsLinux) {
                    // Nodejs on Linux では監視対象のディレクトリの削除で
                    // 監視対象ディレクトリの名前で rename イベントが発生する
                    // Kfswatch では Create, Delete としてイベントが発生するので
                    // それを無視する
                    // 不適切なイベントだが、監視対象ディレクトリと同名のファイルのイベント
                    // との区別が付かないためどうしようもない
                    awaitEvents(
                        Event(KfsEvent.Create, "directory_delete"),
                        Event(KfsEvent.Delete, "directory_delete")
                    )
                }
                stops.awaitItem() shouldBe directory
                watcher.watchingDirectories should beEmpty()
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
            val stops = watcher.onStopFlow.testIn(this)
            watcher.onEventFlow.test(timeout = 5.seconds) {
                watcher.addWait(target)
                if (
                    !Platform.isJvmWindows &&
                    !Platform.isNodejsWindows &&
                    !Platform.isWindows
                ) {
                    // Windows では監視対象の親ディレクトリは移動が失敗する
                    Files.move(parent, "$directory/parent2")
                    when {
                        (Platform.isMacos || Platform.isIos) -> {
                            // macOS, iOS
                            // は監視対象の親ディレクトリの移動は検出されない
                            // 移動後にディレクトリエントリの増減があると
                            // ディレクトリを読み取れないことに気づき監視を停止する
                            Files.writeFile("$directory/parent2/target/child", "")
                            stops.awaitItem() shouldBe target
                        }

                        (Platform.isJvmMacos || Platform.isNodejsMacos) -> {
                            // JVM on macOS, Nodejs on macOS
                            // は監視対象の親ディレクトリの移動で監視が停止する
                            // 監視が停止されたことを検出することはできない
                        }

                        else -> {
                            Files.writeFile("$directory/parent2/target/child", "")
                            awaitEvent(KfsEvent.Create, "child")
                        }
                    }
                }
            }
            stops.ensureAllEventsConsumed()
            stops.cancel()
            errors.ensureAllEventsConsumed()
            errors.cancel()
            watcher.close()
        }
        it("子ディレクトリ内の変化は検出しない") {
            val directory = "$tempDirectory/directory_child".also { mkdirs(it) }
            val child = "$directory/child".also { mkdirs(it) }
            val watcher = createWatcher()
            val errors = watcher.onErrorFlow.testIn(this)
            watcher.onEventFlow.test(timeout = 5.seconds) {
                watcher.addWait(directory)
                mkdirs("$child/child2")
                if (Platform.isMacos || Platform.isIos) {
                    // macOS, iOS では
                    // 子ディレクトリのエントリが変更されると Modify が通知される
                    awaitEvent(KfsEvent.Modify, "child")
                }
            }
            errors.ensureAllEventsConsumed()
            errors.cancel()
            watcher.close()
        }
    }
    describe("監視失敗") {
        it("監視対象が存在しない") {
            val target = "$tempDirectory/no_target"
            val watcher = createWatcher()
            val errors = watcher.onErrorFlow.testIn(this)
            watcher.onEventFlow.test(timeout = 5.seconds) {
                watcher.add(target)
                errors.awaitItem().targetDirectory shouldBe target
            }
            errors.ensureAllEventsConsumed()
            errors.cancel()
            watcher.close()
        }
        it("監視対象がディレクトリではない") {
            val target = "$tempDirectory/file".also { Files.writeFile(it, "") }
            val watcher = createWatcher()
            val errors = watcher.onErrorFlow.testIn(this)
            watcher.onEventFlow.test(timeout = 5.seconds) {
                watcher.add(target)
                errors.awaitItem().targetDirectory shouldBe target
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

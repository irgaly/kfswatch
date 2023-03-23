# Kfswatch

Kotlin Multiplatform File System Watcher Library.

# Features

* A Kotlin Multiplatform library
* Support all platforms
  * JVM - macOS, Linux, Windows
  * Nodejs - macOS, Linux, Windows
  * Android
  * Native iOS
  * Native macOS
  * Native Linux
  * Native Windows
* Watching multiple directories
* Receiving File Events as Flow

# Usage

## Setup

Add Kfswatch as gradle dependency.

### Kotlin Multiplatform:

`build.gradle.kts`

```kotlin
// For Kotlin Multiplatform:
plugins {
  kotlin("multiplatform")
}

kotlin {
  sourceSets {
    commonMain {
      implementation("io.github.irgaly.kfswatch:kfswatch:0.9.0")
    }
  }
  // ...
}
```

### Android or JVM without Kotlin Multiplatform:

`build.gradle.kts`

```kotlin
// For Kotlin/JVM or Kotlin/Android without Kotlin Multiplatform:
plugins {
  id("com.android.application")
  kotlin("android")
  // kotlin("jvm") // for JVM Application
}

dependencies {
  // You can use as JVM library directly
  implementation("io.github.irgaly.kfswatch:kfswatch:0.9.0")
  // ...
}
```

## Use KfsDirectoryWatcher

```kotlin
val scope = CoroutineScope(coroutineContext)
val watcher = KfsDirectoryWatcher(scope)

// Add watching directories, and start watching
watcher.add("path/to/watching/directory1")
watcher.add("path/to/watching/directory2", "path/to/watching/directory3", ...)

// Observe events from Flow
launch {
  watcher.onEventFlow.collect { event: KfsDirectoryWatcherEvent ->
    println("Event received: $event")
  }
}

// Stop watching
watcher.removeAll()

// Release all file watching resources
watcher.close() // or scope.cancel() will trigger watcher.close() automatically
```

## File System Events

Kfswatch supports all platforms, so it supports only simple events.

Kfswatch **does not support recursive directory watching**. Only watching directory's child entry
events will be reported.

KfsDirectoryWatcherEvent

```kotlin
data class KfsDirectoryWatcherEvent(
  /**
   * Watching directory
   */
  val targetDirectory: String,
  /**
   * A file name or directory name of event
   */
  val path: String,
  /**
   * Event type
   */
  val event: KfsEvent
)
```

| Event           | Description                                                     |
|-----------------|-----------------------------------------------------------------|
| KfsEvent.Create | Watching directory's child file or directory entry has created. |
| KfsEvent.Delete | Watching directory's child file or directory entry has deleted. |
| KfsEvent.Modify | Watching directory's child file's content has changed.          |

There are no events for watching directory itself.

## Note: Reliability of Events

Kfswatch uses platform's native File monitoring API,
and map raw file system event to Create, Delete or Modify.
Since File's status will change by moment, that is recommended to check the status by yourself.

For example, checking the file is exists after Create event occurred:

```kotlin
val watcher: KfsDirectoryWatcher = KfsDirectoryWatcher(scope)
//...
launch(Dispatchers.IO) {
  watcher.onEventFlow.collect { event ->
    // For example: JVM File implementation
    val file = File("${event.targetDirectory}/${event.path}")
    if (event.event == KfsEvent.Create) {
      val exists = file.exists()
      if (exists) {
        // ${event.path} file is created and still exists
        // do your operation here
        //...
      }
    }
  }
}
```

`KfsEvent.Modify` event is a fickle event.
For example, when a file's content have updated, `Create` or `Modify` event will happen, that is
depending on platform's File monitoring API.
That is also affected by the file is opened overwrite mode or replaced by other file.

If you'd like to determine that the file is **exactly created**, take a child entry differences.

Taking child entry differences:

```kotlin
// For example: JVM File implementation
val directory: File = File("path/to/directory")
val children: MutableSet<String> = directory.listFiles().toMutableSet()
val watcher: KfsDirectoryWatcher = KfsDirectoryWatcher(scope)
watcher.add("path/to/directory")
launch {
  watcher.onEventFlow.collect { event ->
    if (event.event == KfsEvent.Create || event.event == KfsEvent.Modify) {
      val file = File("${event.targetDirectory}/${event.path}")
      val beforeExists = children.contains(event.path)
      val exists = file.exists()
      when {
        (!beforeExists && exists) -> {
          // When file is created
          //...
        }
        (beforeExists && exists) -> {
          // It seems the file is modified, overwritten or replaced
          //...
        }
      }
      // maintain the child entries
      if (exists) {
        children.add(event.path)
      } else {
        children.remove(event.pth)
      }
    }
  }
}


```

## KfsDirectoryWatcher Features

Constructor options:

```kotlin
KfsDirectoryWatcher(
  // watcher instance will automatically closed with this scope
  scope = scope,
  // a dispatcher that will be used for emitting SharedFlow
  dispatcher = Dispatchers.Default,
  // onRawEventFlow feature is enabled if true
  rawEventEnabled = false,
  // logger for debugging
  logger = object : KfsLogger {
    override fun debug(message: String) {
      println("debug: $message")
    }
    override fun error(message: String) {
      println("error: $message")
    }
  }
)
```

Event flows:

```kotlin
// File System's events
KfsDirectoryWatcher.onEventFlow: Flow<KfsDirectoryWatcherEvent>

// Started watching directory  events
// String = directory path
KfsDirectoryWatcher.onStartFlow: Flow<String>

// Stopped watching directory events
// String = directory path
KfsDirectoryWatcher.onStopFlow: Flow<String>

// File System's Events are overflowed
// String = watching directory or null
KfsDirectoryWatcher.onOverflowFlow: Flow<String?>

// Generic Error events for debugging or error handling
KfsDirectoryWatcher.onErrorFlow: Flow<KfsDirectoryWatcherError>

// File System's raw events for debugging
KfsDirectoryWatcher.onRawEventFlow: Flow<KfsDirectoryWatcherRawEvent>
```

## onRawEventFlow: Flow<KfsDirectoryWatcherRawEvent>

There is a File System's original events flow. This feature is for debugging or escape hatch.

```kotlin
val watcher: KfsDirectoryWatcher = KfsDirectoryWatcher(
  scope = scope,
  // This is needed for onRawEventFlow enabled
  rawEventEnabled = true
)
launch {
  watcher.onRawEventFlow.collect { event: KfsDirectoryWatcherRawEvent ->
    when (event) {
      is AndroidFileObserverRawEvent -> { /* Android's FileObserver Event */ }
      is DarwinKernelQueuesRawEvent -> { /* iOS/macOS's KernelQueue Event */ }
      is NodejsFswatchRawEvent -> { /* Nodejs fs.watch Event */ }
      is JvmWatchServiceRawEvent -> { /* JVM WatchService Event */ }
      is LinuxInotifyRawEvent -> { /* Linux inotify Event */ }
      is WindowsReadDirectoryRawEvent -> { /* Windows ReadDirectoryW Event */ }
    }
  }
}
```

KfsDirectoryWatcherRawEvent classes:

```kotlin
data class AndroidFileObserverRawEvent(
  override val targetDirectory: String,
  val event: Int,
  override val path: String?
) : KfsDirectoryWatcherRawEvent

data class DarwinKernelQueuesRawEvent(
  val ident: ULong,
  val fflags: UInt,
  val filter: Short,
  val flags: UShort,
  val udata: ULong?
) : KfsDirectoryWatcherRawEvent {
  //...
}

data class NodejsFswatchRawEvent(
  override val targetDirectory: String,
  val event: String,
  val filename: String?
) : KfsDirectoryWatcherRawEvent {
  //...
}

data class JvmWatchServiceRawEvent(
  val kind: String,
  val count: Int,
  val context: Any,
  val contextAsPathString: String?
) : KfsDirectoryWatcherRawEvent {
  //...
}

data class LinuxInotifyRawEvent(
  val wd: Int,
  val name: String,
  val mask: UInt,
  val len: UInt,
  val cookie: UInt
) : KfsDirectoryWatcherRawEvent {
  //...
}

data class WindowsReadDirectoryRawEvent(
  override val targetDirectory: String,
  val action: UInt,
  val filename: String,
  val filenameLength: UInt,
  val nextEntryOffset: UInt
) : KfsDirectoryWatcherRawEvent {
  //...
}
```

# Multiplatform

Kfswatch is a Kotlin Multiplatform library.

| Platform                           | Target                                                           | Monitoring System                                                                                                                              | Status                                |
|------------------------------------|------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------|
| Kotlin/JVM<br/>Linux/macOS/Windows | jvm                                                              | [WatchService](https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/nio/file/WatchService.html)                                   | :white_check_mark: Tested             |
| Kotlin/JS<br/>Linux/macOS/Windows  | nodejs                                                           | [fs.watch](https://nodejs.org/api/fs.html#fswatchfilename-options-listener)                                                                    | :white_check_mark: Tested             |
| Kotlin/Android                     | android                                                          | [FileObserver](https://developer.android.com/reference/kotlin/android/os/FileObserver)                                                         | :white_check_mark: Tested             |
| Kotlin/Native iOS                  | iosArm64<br/>iosX64(simulator)<br/>iosSimulatorArm64             | [Kernel Queues](https://developer.apple.com/library/archive/documentation/Darwin/Conceptual/FSEvents_ProgGuide/KernelQueues/KernelQueues.html) | :white_check_mark: Tested             |
| Kotlin/Native watchOS              | watchosArm64<br/>watchosX64(simulator)<br/>watchosSimulatorArm64 | [Kernel Queues](https://developer.apple.com/library/archive/documentation/Darwin/Conceptual/FSEvents_ProgGuide/KernelQueues/KernelQueues.html) | :white_check_mark: (Tested as iosX64) |
| Kotlin/Native tvOS                 | tvosArm64<br/>tvosX64(simulator)<br/>tvosSimulatorArm64          | [Kernel Queues](https://developer.apple.com/library/archive/documentation/Darwin/Conceptual/FSEvents_ProgGuide/KernelQueues/KernelQueues.html) | :white_check_mark: (Tested as iosX64) |
| Kotlin/Native macOS                | macosX64<br/>macosArm64                                          | [Kernel Queues](https://developer.apple.com/library/archive/documentation/Darwin/Conceptual/FSEvents_ProgGuide/KernelQueues/KernelQueues.html) | :white_check_mark: Tested             |
| Kotlin/Native Linux                | linuxX64                                                         | [inotify](https://manpages.ubuntu.com/manpages/bionic/en/man7/inotify.7.html)                                                                  | :white_check_mark: Tested             |
| Kotlin/Native Windows              | mingwX64                                                         | [ReadDirectoryW](https://learn.microsoft.com/en-us/windows/win32/api/winbase/nf-winbase-readdirectorychangesw)                                 | :white_check_mark: Tested             |

Kotlin/JS browser has no File System, so Kfswatch has no operation implementation for that.

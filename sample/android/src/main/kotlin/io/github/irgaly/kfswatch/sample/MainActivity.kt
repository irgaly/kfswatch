package io.github.irgaly.kfswatch.sample

import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import io.github.irgaly.kfswatch.KfsDirectoryWatcher
import io.github.irgaly.kfswatch.KfsLogger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.Transparent.toArgb()
        window.navigationBarColor = Color.Transparent.toArgb()
        WindowInsetsControllerCompat(
            window,
            findViewById(android.R.id.content)
        ).isAppearanceLightStatusBars = true
        setContent {
            MaterialTheme {
                val scope = rememberCoroutineScope()
                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer = object: DefaultLifecycleObserver {
                        override fun onStart(owner: LifecycleOwner) {
                            owner.lifecycleScope.launch {
                                val watcher = KfsDirectoryWatcher(
                                    scope = scope,
                                    logger = object: KfsLogger {
                                        override fun debug(message: String) {
                                            Log.d("watcher", message)
                                        }

                                        override fun error(message: String) {
                                            Log.e("watcher", message)
                                        }
                                    }
                                )
                                watcher.onEventFlow.onEach {
                                    Log.d("watcher", "event = $it")
                                }.launchIn(this)
                                File(cacheDir, "dir").deleteRecursively()
                                watcher.add(cacheDir.path)
                                delay(100.milliseconds)
                                File(cacheDir, "dir").mkdir()
                            }
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose {
                        lifecycleOwner.lifecycle.removeObserver(observer)
                    }
                }
            }
        }
    }
}

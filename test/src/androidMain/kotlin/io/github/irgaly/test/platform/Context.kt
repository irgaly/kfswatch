package io.github.irgaly.test.platform

import androidx.test.platform.app.InstrumentationRegistry.getInstrumentation

@JvmInline
value class AndroidContext(
    val context: android.content.Context
): Context

actual fun getContext(): Context {
    return AndroidContext(getInstrumentation().targetContext)
}

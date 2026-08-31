package io.keepagent.runtime.js

/**
 * Loads the quickjs-ng JNI bridge (`libkeepagent_js.so`).
 * `isLoaded` is false when the native library is absent (e.g. an APK built
 * without the NDK), and callers degrade to a "runtime unavailable" state
 * instead of crashing.
 */
object QuickJs {

    val isLoaded: Boolean = try {
        System.loadLibrary("keepagent_js")
        true
    } catch (_: Throwable) {
        false
    }
}

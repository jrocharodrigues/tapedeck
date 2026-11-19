package com.impecabel.tapedeck.macrobenchmark

import android.os.Process

/**
 * Raises the priority of the server's threads, so that replayed responses keep their configured latency while the app
 * under test is starting.
 *
 * The test process isn't the foreground app. During a cold start the app keeps every core busy, and on low-end devices
 * the server's threads, at default priority, wait for a core before reading each request and writing each response
 * (several milliseconds per request, more under GC or JIT activity in the test process). That delay adds to every
 * replayed request and varies between launches. Audio and display threads use the same mechanism.
 */
internal object ServerThreadPriority {

    private val raised = ThreadLocal<Boolean>()

    /** Raises the calling thread's priority, once per thread. Does nothing off Android (JVM unit tests). */
    fun raiseCurrentThread() {
        if (raised.get() == true) return
        raised.set(true)
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
        } catch (_: RuntimeException) {
            // android.jar stubs in JVM unit tests, or a device refusing the change: keep the default priority
        } catch (_: LinkageError) {
            // No Android classes at all
        }
    }
}

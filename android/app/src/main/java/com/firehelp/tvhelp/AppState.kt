package com.firehelp.tvhelp

import android.os.Handler
import android.os.Looper

/** Shared state between the background service and the screen the family member sees. */
object AppState {
    enum class Phase { CONNECTING, READY, REQUEST, IN_SESSION }

    @Volatile var phase: Phase = Phase.CONNECTING
    @Volatile var code: String = ""
    @Volatile var message: String = "Connecting…"
    @Volatile var adbReady: Boolean = false
    @Volatile var adbMessage: String = ""

    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<() -> Unit>()

    fun addListener(l: () -> Unit) = synchronized(listeners) { listeners.add(l) }
    fun removeListener(l: () -> Unit) = synchronized(listeners) { listeners.remove(l) }

    fun update(block: AppState.() -> Unit) {
        block()
        val copy = synchronized(listeners) { listeners.toList() }
        main.post { copy.forEach { it() } }
    }
}

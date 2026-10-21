package com.feedback.demo.universal

import java.util.concurrent.CopyOnWriteArrayList

/** 用户授权的 MediaProjection 会话的进程内状态与回调。 */
object ScreenCaptureSession {
    enum class State { STOPPED, STARTING, READY }

    @Volatile var state: State = State.STOPPED
        internal set
    private val listeners = CopyOnWriteArrayList<(State) -> Unit>()

    fun addListener(listener: (State) -> Unit) {
        listeners += listener
        listener(state)
    }

    fun removeListener(listener: (State) -> Unit) {
        listeners -= listener
    }

    internal fun update(next: State) {
        state = next
        listeners.forEach { it(next) }
    }
}

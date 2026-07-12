package com.ryu.sonyremote.data

internal class CurrentSessionGate<T : Any> {
    private val lock = Any()

    @Volatile
    private var current: T? = null

    fun current(): T? = current

    fun isCurrent(expected: T): Boolean = current === expected

    fun install(value: T, action: (T) -> Unit) {
        synchronized(lock) {
            current = value
            action(value)
        }
    }

    fun withCurrent(expected: T, action: (T) -> Unit): Boolean = synchronized(lock) {
        if (current !== expected) return@synchronized false
        action(expected)
        true
    }

    fun invalidate(expected: T, action: (T) -> Unit): Boolean = synchronized(lock) {
        if (current !== expected) return@synchronized false
        current = null
        action(expected)
        true
    }

    fun clear(action: (T?) -> Unit): T? = synchronized(lock) {
        val previous = current
        current = null
        action(previous)
        previous
    }
}

internal enum class LiveViewEndDecision {
    IntentionalStop,
    KeepSession,
    NetworkLost,
}

internal fun classifyLiveViewEnd(
    coroutineActive: Boolean,
    networkAvailable: Boolean,
): LiveViewEndDecision = when {
    !coroutineActive -> LiveViewEndDecision.IntentionalStop
    !networkAvailable -> LiveViewEndDecision.NetworkLost
    else -> LiveViewEndDecision.KeepSession
}

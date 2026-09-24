package com.jack.micbridge.service

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serializes side effects for edge-triggered boundaries while letting a newer event invalidate
 * an action that is already running. Callers must still re-check [isCurrent] after suspension.
 */
internal class LatestEventMutex {
    private val latest = AtomicLong(0L)
    private val mutex = Mutex()
    private val boundaryLock = Any()

    fun current(): Long = latest.get()

    fun advance(): Long = advance {}

    /**
     * Linearizes invalidation with the synchronous fail-closed side effect. A final publication
     * guarded by [commitIfCurrent] therefore either wins before this action (and is then revoked)
     * or observes the new event and cannot run afterwards.
     */
    fun advance(action: () -> Unit): Long = advanceIf({ true }, action)!!

    /** Atomically checks ownership before invalidating it, for stale server error callbacks. */
    fun advanceIf(condition: () -> Boolean, action: () -> Unit): Long? =
        synchronized(boundaryLock) {
            if (!condition()) {
                null
            } else {
                latest.incrementAndGet().also { action() }
            }
        }

    fun isCurrent(event: Long): Boolean = event == latest.get()

    fun runAtBoundary(action: () -> Unit) = synchronized(boundaryLock) { action() }

    fun commitIfCurrent(event: Long, action: () -> Unit): Boolean =
        synchronized(boundaryLock) {
            if (!isCurrent(event)) {
                false
            } else {
                action()
                true
            }
        }

    suspend fun runIfCurrent(event: Long, action: suspend () -> Unit): Boolean =
        mutex.withLock {
            if (!isCurrent(event)) {
                false
            } else {
                action()
                true
            }
        }
}

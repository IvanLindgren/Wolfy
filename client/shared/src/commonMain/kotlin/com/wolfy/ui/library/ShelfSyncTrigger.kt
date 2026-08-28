package com.wolfy.ui.library

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Коалесцирует запросы быстрой синхронизации после действий с полками.
 *
 * Перетаскивание может послать несколько событий подряд, поэтому не создаём
 * по сетевому запросу на каждое событие: пока обмен идёт, достаточно одного
 * следующего запуска. Сам trigger живёт в viewModelScope и отменяется вместе
 * с экранным владельцем.
 */
internal class ShelfSyncTrigger(
    scope: CoroutineScope,
    private val sync: suspend () -> Unit,
) {
    private val requests = Channel<Unit>(capacity = Channel.CONFLATED)
    private var workerRunning = false
    private var pending = false
    private val lock = Any()

    init {
        scope.launch {
            for (ignored in requests) {
                while (true) {
                    synchronized(lock) {
                        if (!pending) {
                            workerRunning = false
                            break
                        }
                        pending = false
                    }
                    try {
                        sync()
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // A transient network error must not kill the worker;
                        // the next user action or pull can retry the sync.
                    }
                }
            }
        }
    }

    fun request() {
        synchronized(lock) {
            pending = true
            if (workerRunning) return
            workerRunning = true
        }
        requests.trySend(Unit)
    }
}

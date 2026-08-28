package com.wolfy.ui.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class ShelfSyncTriggerTest {
    @Test
    fun requestDuringSyncSchedulesOneMoreRun() = runTest {
        var calls = 0
        val release = CompletableDeferred<Unit>()
        val trigger = ShelfSyncTrigger(backgroundScope) {
            calls += 1
            if (calls == 1) release.await()
        }
        runCurrent()

        trigger.request()
        runCurrent()
        trigger.request()
        trigger.request()
        assertEquals(1, calls)

        release.complete(Unit)
        runCurrent()

        assertEquals(2, calls)
    }

    @Test
    fun failedSyncDoesNotDisableLaterRequests() = runTest {
        var calls = 0
        var fail = true
        val trigger = ShelfSyncTrigger(backgroundScope) {
            calls += 1
            if (fail) {
                fail = false
                error("temporary failure")
            }
        }

        trigger.request()
        runCurrent()
        assertEquals(1, calls)

        trigger.request()
        runCurrent()
        assertEquals(2, calls)
    }
}

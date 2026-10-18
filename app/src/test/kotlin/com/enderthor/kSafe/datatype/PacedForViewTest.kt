package com.enderthor.kSafe.datatype

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real clock on purpose: the pacer must satisfy karoo-ext 1.1.9's own rule, so the fake
 * below re-implements ViewEmitter.updateView independently (900 ms, stamped on entry) —
 * NOT via COALESCE_GUARD_MS, or a regression of that constant would still pass.
 */
class PacedForViewTest {

    private class SdkEmitter {
        private var lastAccepted = 0L
        val accepted = mutableListOf<Int>()
        var dropped = 0
        fun updateView(v: Int) {
            val now = System.currentTimeMillis()
            if (now - lastAccepted < 900) { dropped++; return }
            lastAccepted = now
            accepted += v
        }
    }

    @Test
    fun `burst after a seed is never dropped and ends on the latest value`() = runBlocking {
        val sdk = SdkEmitter()
        sdk.updateView(0)
        val seed = System.currentTimeMillis()
        flowOf(1, 2, 3).pacedForView(seed).collect { sdk.updateView(it) }
        assertEquals(0, sdk.dropped)
        assertEquals(3, sdk.accepted.last())
        assertTrue("not conflated: ${sdk.accepted}", sdk.accepted.size < 4)
    }

    @Test
    fun `value arriving during the hold replaces the one that started it`() = runBlocking {
        val sdk = SdkEmitter()
        sdk.updateView(0)
        val seed = System.currentTimeMillis()
        flow { emit(1); delay(300); emit(2) }.pacedForView(seed).collect { sdk.updateView(it) }
        assertEquals(listOf(0, 2), sdk.accepted)
    }

    @Test
    fun `StateFlow upstream paints the newest value after a hold`() = runBlocking {
        // StateFlow.conflate() fuses to the StateFlow itself, so produceIn gets a BUFFERED
        // channel: the status fields' upstream is not conflated by the operator.
        val sf = MutableStateFlow(1)
        var painted: Int? = null
        val job = launch {
            sf.pacedForView(System.currentTimeMillis()).take(1).collect { painted = it }
        }
        delay(100); sf.value = 2
        delay(50); sf.value = 3
        delay(50); sf.value = 4
        job.join()
        assertEquals(4, painted)
    }

    @Test
    fun `wall clock recovering after a backward jump releases the hold promptly`() = runBlocking {
        // Last render stamped at 10_000; the clock then jumps back an hour (wait ≈ 1 h) and
        // is restored 100 ms later. The hold must notice the recovery, not sleep the hour.
        var clock = 10_000L - 3_600_000L
        val t0 = System.currentTimeMillis()
        val job = launch { awaitRenderSlot(10_000L) { clock } }
        delay(100); clock = 20_000L
        job.join()
        assertTrue(System.currentTimeMillis() - t0 < 1_500)
    }

    @Test
    fun `no seed renders without a hold`() = runBlocking {
        val t0 = System.currentTimeMillis()
        MutableSharedFlow<Int>(replay = 1).apply { tryEmit(7) }
            .pacedForView().take(1).toList()
        assertTrue(System.currentTimeMillis() - t0 < 800)
    }
}

package com.enderthor.kSafe.datatype

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real clock on purpose: the pacer must match karoo-ext's System.currentTimeMillis check. */
class PacedForViewTest {

    @Test
    fun `burst after a seed waits a full slot and lands the latest value`() = runBlocking {
        val seed = System.currentTimeMillis()
        val times = mutableListOf<Long>()
        val out = flowOf(1, 2, 3).pacedForView(seed)
            .onEach { times += System.currentTimeMillis() }
            .toList()
        // Conflated: 1 is pulled first, then 3 replaces 2 while 1 waits.
        assertEquals(3, out.last())
        assertTrue("first frame too soon after seed", times.first() - seed >= COALESCE_GUARD_MS - 5)
        times.zipWithNext().forEach { (a, b) -> assertTrue("frames ${b - a} ms apart", b - a >= COALESCE_GUARD_MS - 5) }
    }

    @Test
    fun `no seed renders immediately`() = runBlocking {
        val t0 = System.currentTimeMillis()
        MutableSharedFlow<Int>(replay = 1).apply { tryEmit(7) }
            .pacedForView().take(1).toList()
        assertTrue(System.currentTimeMillis() - t0 < 300)
    }
}

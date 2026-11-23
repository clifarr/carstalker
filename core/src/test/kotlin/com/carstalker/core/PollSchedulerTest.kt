package com.carstalker.core

import com.carstalker.core.obd.PollScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PollSchedulerTest {

    // 0C/0D fast, 05/11 medium, 21 slow.
    private val requested = listOf(0x0C, 0x0D, 0x05, 0x11, 0x21)
    private val allSupported = requested.toSet()

    @Test
    fun `the first cycle fills every tile, later cycles favour fast values`() {
        val scheduler = PollScheduler()
        val first = scheduler.next(requested, allSupported)
        assertTrue("first cycle should fill the dashboard", first.containsAll(requested))

        scheduler.advance()
        val second = scheduler.next(requested, allSupported)
        assertTrue(0x0C in second)
        assertTrue(0x0D in second)
        assertFalse("medium values wait their turn", 0x05 in second)
        assertFalse("slow values wait their turn", 0x21 in second)
    }

    @Test
    fun `medium values appear on even cycles and slow ones every fourth`() {
        val scheduler = PollScheduler()
        repeat(2) { scheduler.advance() }
        val third = scheduler.next(requested, allSupported) // cycle 2
        assertTrue("coolant is medium", 0x05 in third)
        assertFalse("PID 21 is slow", 0x21 in third)

        repeat(2) { scheduler.advance() }
        val fifth = scheduler.next(requested, allSupported) // cycle 4
        assertTrue("PID 21 should return on cycle 4", 0x21 in fifth)
    }

    @Test
    fun `a full sweep of fast values costs far fewer commands than polling everything`() {
        val scheduler = PollScheduler()
        var commands = 0
        repeat(4) {
            commands += scheduler.next(requested, allSupported).size
            scheduler.advance()
        }
        // Polling everything for four cycles would be 20 requests: 5 + 2 + 4 + 2 = 13.
        assertEquals(13, commands)
    }

    @Test
    fun `unsupported parameters are never requested`() {
        val scheduler = PollScheduler()
        val supported = setOf(0x0C, 0x0D)
        repeat(4) {
            val polled = scheduler.next(requested, supported)
            assertFalse(0x05 in polled)
            scheduler.advance()
        }
    }

    @Test
    fun `parameters that stop answering are dropped`() {
        val scheduler = PollScheduler(silentLimit = 2)
        scheduler.record(0x05, answered = false)
        assertFalse(scheduler.isSilent(0x05))
        scheduler.record(0x05, answered = false)
        assertTrue(scheduler.isSilent(0x05))

        repeat(4) {
            assertFalse(0x05 in scheduler.next(requested, allSupported))
            scheduler.advance()
        }

        scheduler.reset()
        assertFalse(scheduler.isSilent(0x05))
    }

    @Test
    fun `a successful answer clears the miss counter`() {
        val scheduler = PollScheduler(silentLimit = 2)
        scheduler.record(0x05, answered = false)
        scheduler.record(0x05, answered = true)
        scheduler.record(0x05, answered = false)
        assertFalse(scheduler.isSilent(0x05))
    }

    @Test
    fun `custom pids bypass the support bitmap`() {
        val scheduler = PollScheduler()
        val custom = setOf(0x0B)
        val polled = scheduler.next(listOf(0x0B), supported = setOf(0x0C), exempt = custom)
        assertEquals(listOf(0x0B), polled)
    }
}

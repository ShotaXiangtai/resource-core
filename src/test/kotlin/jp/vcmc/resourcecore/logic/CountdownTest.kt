package jp.vcmc.resourcecore.logic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CountdownTest {
    private val steps = listOf(CountdownStep(300), CountdownStep(60), CountdownStep(10))

    @Test
    fun `announces the first step immediately and runs for the longest step`() {
        val tracker = CountdownTracker(steps, 0)
        assertEquals(300, tracker.durationSeconds)
        val poll = tracker.poll(0)
        assertEquals(300, poll.announce?.seconds)
        assertEquals(300, poll.remainingSeconds)
        assertFalse(poll.finished)
    }

    @Test
    fun `each step is announced exactly once`() {
        val tracker = CountdownTracker(steps, 0)
        tracker.poll(0)
        assertNull(tracker.poll(1_000).announce)
        assertEquals(60, tracker.poll(240_000).announce?.seconds)
        assertNull(tracker.poll(240_500).announce)
        assertEquals(10, tracker.poll(290_000).announce?.seconds)
    }

    @Test
    fun `finishes when the time is up`() {
        val tracker = CountdownTracker(steps, 0)
        assertFalse(tracker.poll(299_999).finished)
        assertTrue(tracker.poll(300_000).finished)
    }

    @Test
    fun `only the latest due step is announced after a stall`() {
        val tracker = CountdownTracker(steps, 0)
        val poll = tracker.poll(295_000)
        assertEquals(10, poll.announce?.seconds)
        assertNull(tracker.poll(296_000).announce)
    }

    @Test
    fun `step order in the config does not matter`() {
        val tracker = CountdownTracker(listOf(CountdownStep(10), CountdownStep(300), CountdownStep(60)), 0)
        assertEquals(300, tracker.durationSeconds)
        assertEquals(300, tracker.poll(0).announce?.seconds)
    }

    @Test
    fun `clock going backwards does not extend the countdown`() {
        val tracker = CountdownTracker(steps, 1_000)
        assertEquals(300, tracker.remainingSeconds(0))
    }

    @Test
    fun `formats durations`() {
        fun format(seconds: Int) = formatDuration(seconds, "時間", "分", "秒")
        assertEquals("0秒", format(0))
        assertEquals("10秒", format(10))
        assertEquals("1分", format(60))
        assertEquals("1分30秒", format(90))
        assertEquals("5分", format(300))
        assertEquals("1時間1分1秒", format(3661))
    }
}

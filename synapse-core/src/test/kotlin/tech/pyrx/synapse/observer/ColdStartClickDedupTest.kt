/*
 * ColdStartClickDedupTest.kt
 * PYRXSynapseTests — Android
 *
 * Exercises the non-negotiable Risk Register item #1 invariant:
 *
 *   "If the SAME push (same pushLogId) caused a cold-start launch AND
 *   subsequently arrived through handleNotificationTap within 5
 *   seconds, only PushReceivedColdStart fires — the paired PushClicked
 *   is suppressed."
 *
 * Uses an injected `nowMillis` lambda so wall-clock progression is
 * deterministic — no `Thread.sleep` here.
 */

package tech.pyrx.synapse.observer

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ColdStartClickDedupTest {
    /**
     * Holds a single Long so the lambda passed to [ColdStartClickDedup]
     * can read + mutate it from the test body. Tests advance the clock
     * by assignment.
     */
    private class FakeClock(initialMillis: Long = 0L) {
        var now: Long = initialMillis
        val lambda: () -> Long = { now }
    }

    @Test
    fun `shouldSuppressClick returns false when no record exists`() {
        val dedup = ColdStartClickDedup()
        assertFalse(dedup.shouldSuppressClick("any-id"))
    }

    @Test
    fun `shouldSuppressClick returns true within the window after recordColdStart`() {
        val clock = FakeClock()
        val dedup = ColdStartClickDedup(nowMillis = clock.lambda)

        dedup.recordColdStart("push-1")
        clock.now += 100L // 100ms later, well within the 5s window

        assertTrue(dedup.shouldSuppressClick("push-1"))
    }

    @Test
    fun `shouldSuppressClick returns false after the window expires`() {
        val clock = FakeClock()
        val dedup = ColdStartClickDedup(nowMillis = clock.lambda)

        dedup.recordColdStart("push-1")
        clock.now += ColdStartClickDedup.DEFAULT_WINDOW_MILLIS + 1L

        assertFalse(dedup.shouldSuppressClick("push-1"))
    }

    @Test
    fun `shouldSuppressClick consumes the entry so a second tap fires`() {
        val clock = FakeClock()
        val dedup = ColdStartClickDedup(nowMillis = clock.lambda)

        dedup.recordColdStart("push-1")
        // First tap: cold-start collision — suppress.
        assertTrue(dedup.shouldSuppressClick("push-1"))
        // Second tap of the same notification within the window: the user
        // legitimately re-tapped, fire normally.
        clock.now += 200L
        assertFalse(dedup.shouldSuppressClick("push-1"))
    }

    @Test
    fun `different pushLogIds do not collide`() {
        val clock = FakeClock()
        val dedup = ColdStartClickDedup(nowMillis = clock.lambda)

        dedup.recordColdStart("push-1")
        // Tap of a different notification — must NOT be suppressed.
        assertFalse(dedup.shouldSuppressClick("push-2"))
        // The original suppression entry must still be intact.
        assertTrue(dedup.shouldSuppressClick("push-1"))
    }

    @Test
    fun `stale entries are pruned on access so the map stays bounded`() {
        val clock = FakeClock()
        val dedup = ColdStartClickDedup(nowMillis = clock.lambda)

        // Record three entries; advance well past the window; then
        // record one fresh entry. The stale three should drop on prune.
        dedup.recordColdStart("a")
        dedup.recordColdStart("b")
        dedup.recordColdStart("c")
        assertEquals(3, dedup.size())

        clock.now += ColdStartClickDedup.DEFAULT_WINDOW_MILLIS + 1L
        dedup.recordColdStart("d") // triggers pruneStale via the record path

        assertEquals(1, dedup.size(), "stale entries must be pruned")
        assertFalse(dedup.shouldSuppressClick("a"), "a expired")
        assertFalse(dedup.shouldSuppressClick("b"), "b expired")
        assertFalse(dedup.shouldSuppressClick("c"), "c expired")
        // shouldSuppressClick("d") consumes — assert before tearing down
        assertTrue(dedup.shouldSuppressClick("d"), "d is fresh")
    }

    @Test
    fun `clear drops all entries`() {
        val clock = FakeClock()
        val dedup = ColdStartClickDedup(nowMillis = clock.lambda)

        dedup.recordColdStart("push-1")
        dedup.recordColdStart("push-2")
        assertEquals(2, dedup.size())

        dedup.clear()

        assertEquals(0, dedup.size())
        assertFalse(dedup.shouldSuppressClick("push-1"))
        assertFalse(dedup.shouldSuppressClick("push-2"))
    }

    @Test
    fun `custom window honoured`() {
        val clock = FakeClock()
        val dedup = ColdStartClickDedup(windowMillis = 100L, nowMillis = clock.lambda)

        dedup.recordColdStart("push-1")
        clock.now += 99L
        assertTrue(dedup.shouldSuppressClick("push-1"), "within custom window")

        dedup.recordColdStart("push-2")
        clock.now += 101L
        assertFalse(dedup.shouldSuppressClick("push-2"), "past custom window")
    }
}

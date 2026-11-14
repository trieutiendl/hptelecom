package vn.vltelecom

import org.junit.Assert.*
import org.junit.Test

class MxRefreshScheduleTest {
    @Test fun followsFourDeadlinesAndThenStops() {
        val started = 1_000_000L
        var state: MxRefreshSchedule.State? = MxRefreshSchedule.begin(started)
        var now = started
        for (delay in listOf(15_000L, 30_000L, 60_000L, 120_000L)) {
            val current = checkNotNull(state)
            assertEquals(now + delay, current.dueAt)
            assertFalse(MxRefreshSchedule.due(current, current.dueAt - 1, true, true))
            now = current.dueAt
            assertTrue(MxRefreshSchedule.due(current, now, true, true))
            state = MxRefreshSchedule.consume(current, now)
        }
        assertNull("No fifth follow-up or perpetual polling", state)
    }

    @Test fun backgroundAndDifferentAccountNeverConsumeOrTrigger() {
        val current = MxRefreshSchedule.begin(1_000_000)
        assertFalse(MxRefreshSchedule.due(current, 9_000_000, false, true))
        assertFalse(MxRefreshSchedule.due(current, 9_000_000, true, false))
        assertFalse(MxRefreshSchedule.due(current, 9_000_000, false, false))
        assertEquals(0, current.attempts)
        assertTrue("Foreground resume can use the overdue attempt", MxRefreshSchedule.due(current, 9_000_000, true, true))
    }

    @Test fun restoredStateRetainsItsRemainingBudget() {
        var state = MxRefreshSchedule.begin(1_000_000)
        state = checkNotNull(MxRefreshSchedule.consume(state, state.dueAt))
        state = checkNotNull(MxRefreshSchedule.consume(state, state.dueAt))
        val restored = MxRefreshSchedule.State(state.attempts, state.dueAt)
        assertEquals(2, restored.attempts)
        val last = checkNotNull(MxRefreshSchedule.consume(restored, restored.dueAt))
        assertEquals(3, last.attempts)
        assertNull(MxRefreshSchedule.consume(last, last.dueAt))
    }

    @Test fun invalidOrExhaustedStateIsNeverEligible() {
        for (state in listOf(MxRefreshSchedule.State(-1, 10), MxRefreshSchedule.State(4, 10), MxRefreshSchedule.State(0, 0))) {
            assertFalse(MxRefreshSchedule.valid(state))
            assertFalse(MxRefreshSchedule.due(state, 99_999, true, true))
            assertNull(MxRefreshSchedule.consume(state, 99_999))
        }
    }
}

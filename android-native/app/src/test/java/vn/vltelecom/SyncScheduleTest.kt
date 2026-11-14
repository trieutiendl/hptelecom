package vn.vltelecom

import org.junit.Assert.*
import org.junit.Test

class SyncScheduleTest {
    @Test fun unchangedApplicationStaysIdleWithoutPeriodicPolling() {
        val schedule = SyncSchedule()
        for (now in listOf(0L, 15_000L, 60_000L, 3_600_000L, 86_400_000L)) {
            assertNull(schedule.delayMillis(now))
            assertNull(schedule.take(now))
        }
    }

    @Test fun rapidEditsWaitUntilTenSecondsAfterTheLastEdit() {
        val schedule = SyncSchedule()
        schedule.changed(0L)
        schedule.changed(5_000L)
        schedule.changed(9_999L)

        assertNull(schedule.take(10_000L))
        assertEquals(9_998L, requireNotNull(schedule.delayMillis(10_001L)))
        assertNull(schedule.take(19_998L))
        assertTrue(requireNotNull(schedule.take(19_999L)).receive)
        assertNull(schedule.take(20_000L))
        assertNull(schedule.delayMillis(3_600_000L))
    }

    @Test fun manualSyncCanConsumePendingEditsImmediately() {
        val schedule = SyncSchedule()
        schedule.changed(100L)
        schedule.request(500L, receive = true, immediate = true)

        assertEquals(0L, requireNotNull(schedule.delayMillis(500L)))
        assertTrue(requireNotNull(schedule.take(500L)).receive)
        assertNull(schedule.take(501L))
        assertNull(schedule.delayMillis(10_100L))
    }

    @Test fun editDuringAnInFlightBatchSchedulesAnotherBatch() {
        val schedule = SyncSchedule()
        schedule.request(0L, receive = true, immediate = true)
        val inFlight = requireNotNull(schedule.take(0L))

        schedule.changed(100L)
        assertTrue(inFlight.receive)
        assertNull(schedule.take(10_099L))
        assertTrue(requireNotNull(schedule.take(10_100L)).receive)
        assertNull(schedule.delayMillis(10_101L))
    }

    @Test fun localChangesDebounceAndCheckForRemoteUpdatesOnce() {
        val schedule = SyncSchedule()
        schedule.changed(0L)
        schedule.changed(5_000L)
        assertNull(schedule.take(14_999L))
        assertTrue(requireNotNull(schedule.take(15_000L)).receive)
        assertNull(schedule.take(15_001L))

        schedule.changed(20_000L)
        assertTrue(requireNotNull(schedule.take(30_000L)).receive)
    }

    @Test fun ordinaryWakeupsDoNotBypassTheEditDebounce() {
        val schedule = SyncSchedule()
        schedule.changed(0L)
        schedule.request(1_000L)
        schedule.request(2_000L, receive = true)

        assertNull(schedule.take(3_000L))
        assertEquals(7_000L, requireNotNull(schedule.delayMillis(3_000L)))
        assertTrue(requireNotNull(schedule.take(10_000L)).receive)
    }

    @Test fun repeatedChecksCoalesceWithoutPostponingExistingWork() {
        val schedule = SyncSchedule()
        schedule.request(100L)
        schedule.request(500L, receive = true)
        schedule.request(1_000L)

        assertNull(schedule.take(1_099L))
        assertTrue(requireNotNull(schedule.take(1_100L)).receive)
        assertNull(schedule.take(2_000L))
        assertNull(schedule.delayMillis(86_400_000L))
    }

    @Test fun laterEditPreservesAnExplicitRemoteRefreshRequest() {
        val schedule = SyncSchedule()
        schedule.request(0L, receive = true)
        schedule.changed(500L)

        assertNull(schedule.take(1_000L))
        assertTrue(requireNotNull(schedule.take(10_500L)).receive)
    }

    @Test fun overdueWorkReportsZeroDelayAndRunsOnlyOnce() {
        val schedule = SyncSchedule()
        schedule.changed(0L)

        assertEquals(0L, requireNotNull(schedule.delayMillis(60_000L)))
        assertNotNull(schedule.take(60_000L))
        assertNull(schedule.delayMillis(60_000L))
        assertNull(schedule.take(60_000L))
    }

    @Test fun resetDiscardsThePreviousAccountsWorkAndReceiveFlag() {
        val schedule = SyncSchedule()
        schedule.changed(0L)
        schedule.request(500L, receive = true)
        schedule.reset()

        assertNull(schedule.delayMillis(10_000L))
        assertNull(schedule.take(10_000L))
        schedule.changed(20_000L)
        assertTrue(requireNotNull(schedule.take(30_000L)).receive)
        assertNull(schedule.delayMillis(30_000L))
    }
}

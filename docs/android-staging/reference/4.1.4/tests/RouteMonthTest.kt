package vn.vltelecom

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class RouteMonthTest {
    @Test fun groupsUsingVietnamLocalMonth() {
        assertEquals("2026-10", RouteMonth.key(Instant.parse("2026-09-30T17:05:00Z").epochSecond))
    }

    @Test fun missingCreationDateHasNoMonth() {
        assertEquals("", RouteMonth.key(0))
    }

    @Test fun groupsIncidentAndRelocationReportDatesByMonth() {
        assertEquals("2026-10", RouteMonth.reportKey("05/10/2026"))
        assertEquals("2026-09", RouteMonth.reportKey("2026-09-30"))
        assertEquals("", RouteMonth.reportKey(""))
    }
}

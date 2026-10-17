package vn.vltelecom

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime

class IncidentReceivedOrderTest {
    @Test fun incidentDaysAndSameDayTimesAreAscendingInsteadOfTitleOrder() {
        val received = mapOf("A" to "3/10/2026 08:00", "B" to "2/10/2026 16:30", "C" to "1/10/2026 23:59", "D" to "2/10/2026 08:15")
        assertEquals(listOf("C", "D", "B", "A"), IncidentReceivedOrder.sorted(received.keys.toList()) { IncidentReceivedOrder.timestamp(received.getValue(it)) })
    }

    @Test fun calendarBoundariesUseDatesNotLexicographicText() {
        val received = listOf("01/01/2027 00:00", "31/12/2026 23:59", "2/10/2026", "10/10/2026")
        assertEquals(listOf(received[2], received[3], received[1], received[0]), IncidentReceivedOrder.sorted(received, IncidentReceivedOrder::timestamp))
        assertEquals(LocalDateTime.of(2026, 10, 2, 0, 0), IncidentReceivedOrder.timestamp("2/10/2026"))
    }

    @Test fun missingInvalidAndImpossibleTimesGoLastWithoutChangingTies() {
        val rows = listOf("bad", "first tie", "empty", "second tie", "early", "bad date")
        val received = mapOf("bad" to "02/10/2026 25:00", "first tie" to "02/10/2026 09:00", "empty" to "", "second tie" to "02/10/2026 09:00", "early" to "01/10/2026", "bad date" to "31/02/2026 10:00")
        assertEquals(listOf("early", "first tie", "second tie", "bad", "empty", "bad date"), IncidentReceivedOrder.sorted(rows) { IncidentReceivedOrder.timestamp(received.getValue(it)) })
        for (value in listOf("02/10/2026 24:00", "02/10/2026 10:60", "02/10/2026 10:00 extra", "29/02/2025")) assertNull(value, IncidentReceivedOrder.timestamp(value))
    }

    @Test fun receivedRemainsAuthoritativeAfterReportDateOrOtherFieldsChange() {
        val expected = IncidentReceivedOrder.timestamp("01/10/2026 09:00")
        assertEquals(expected, IncidentReceivedOrder.fromReport("""{"received":"01/10/2026 09:00","date":"08/10/2026","solution":"updated"}"""))
        assertNull(IncidentReceivedOrder.fromReport("""{"received":"","date":"01/10/2026"}"""))
        assertNull(IncidentReceivedOrder.fromReport("invalid JSON"))
    }

    @Test fun dossierWithoutSharedReceivedUsesEarliestValidRouteReceived() {
        val document = """{"routes":[{"report":{"received":"03/10/2026 14:00"}},{"report":{"received":"invalid"}},{"report":{"received":"02/10/2026 08:00"}}]}"""
        assertEquals(IncidentReceivedOrder.timestamp("02/10/2026 08:00"), IncidentReceivedOrder.fromReport(document))
        assertEquals(IncidentReceivedOrder.timestamp("01/10/2026 10:00"), IncidentReceivedOrder.fromReport("""{"received":"01/10/2026 10:00","routes":[{"report":{"received":"02/10/2026"}}]}"""))
    }

    @Test fun isoAndLegacyDashDatesAreAcceptedButPartialDatesAreNot() {
        val expected = LocalDateTime.of(2026, 10, 2, 8, 5, 3)
        for (value in listOf("02/10/2026 08:05:03", "2-10-2026 8:5:3", "2026-10-02T08:05:03", "2026-10-02 08:05:03")) assertEquals(value, expected, IncidentReceivedOrder.timestamp(value))
        assertNull(IncidentReceivedOrder.timestamp("02/10"))
    }

    @Test fun importedIsoOffsetsUseVietnamTimeAcrossDateBoundaries() {
        val expected = LocalDateTime.of(2026, 10, 2, 1, 30)
        for (value in listOf("2026-10-01T18:30:00Z", "2026-10-02T01:30:00+07:00", "2026-10-02 02:30:00+08:00")) {
            assertEquals(value, expected, IncidentReceivedOrder.timestamp(value))
        }
        val rows = listOf("02/10/2026 02:00", "2026-10-01T18:30:00Z", "01/10/2026 23:59")
        assertEquals(listOf(rows[2], rows[1], rows[0]), IncidentReceivedOrder.sorted(rows, IncidentReceivedOrder::timestamp))
    }

    @Test fun importedAmPmTimesHaveTheSameOrderingAsThe24HourForm() {
        assertEquals(IncidentReceivedOrder.timestamp("02/10/2026 00:05"), IncidentReceivedOrder.timestamp("2/10/2026 12:05 AM"))
        assertEquals(IncidentReceivedOrder.timestamp("02/10/2026 12:05:03"), IncidentReceivedOrder.timestamp("2/10/2026 12:05:03 pm"))
        assertEquals(IncidentReceivedOrder.timestamp("02/10/2026 20:15"), IncidentReceivedOrder.timestamp("2/10/2026 8:15 PM"))
        assertNull(IncidentReceivedOrder.timestamp("02/10/2026 13:00 PM"))
    }
}

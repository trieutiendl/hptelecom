package vn.vltelecom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportMonthSelectionTest {
    @Test fun monthChoicesShowAllThenAvailableMonthsNewestFirst() {
        assertEquals(listOf(null, "2026-10", "2026-09", ""), ReportMonthSelection.choices(listOf("2026-09", "", "2026-10", "2026-09")))
    }

    @Test fun selectedMonthFiltersOnlyThatMonthAndKeepsUndatedDistinct() {
        assertTrue(ReportMonthSelection.matches("2026-10", null))
        assertTrue(ReportMonthSelection.matches("2026-10", "2026-10"))
        assertFalse(ReportMonthSelection.matches("2026-09", "2026-10"))
        assertTrue(ReportMonthSelection.matches("", ""))
        assertFalse(ReportMonthSelection.matches("2026-10", ""))
    }

    @Test fun monthChoicesHaveReadableVietnameseLabels() {
        assertEquals("Tất cả tháng", ReportMonthSelection.label(null))
        assertEquals("Tháng 10/2026", ReportMonthSelection.label("2026-10"))
        assertEquals("Chưa rõ tháng", ReportMonthSelection.label(""))
    }
}

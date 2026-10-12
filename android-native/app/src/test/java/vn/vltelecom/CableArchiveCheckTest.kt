package vn.vltelecom

import org.junit.Assert.assertEquals
import org.junit.Test

class CableArchiveCheckTest {
    @Test fun archivesOnlyMatchingCompletedAcknowledgedData() {
        assertEquals(true, CableArchiveCheck.allowed(true, true, false, 8, 8, "abc", "abc", false))
    }

    @Test fun keepsDataUntilServerAndLocalStateMatch() {
        assertEquals(false, CableArchiveCheck.allowed(true, false, false, 8, 8, "abc", "abc", false))
        assertEquals(false, CableArchiveCheck.allowed(true, true, true, 8, 8, "abc", "abc", false))
        assertEquals(false, CableArchiveCheck.allowed(true, true, false, 8, 9, "abc", "abc", false))
        assertEquals(false, CableArchiveCheck.allowed(true, true, false, 8, 8, "abc", "old", false))
        assertEquals(false, CableArchiveCheck.allowed(true, true, false, 8, 8, "abc", "abc", true))
    }
}

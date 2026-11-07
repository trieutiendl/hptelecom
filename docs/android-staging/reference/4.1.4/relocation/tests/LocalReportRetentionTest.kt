package vn.vltelecom

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class LocalReportRetentionTest {
    private fun acknowledged(owner: Boolean = true, localComplete: Boolean = true, serverComplete: Boolean = true,
        pending: Boolean = false, refresh: Boolean = false, canReceive: Boolean = true,
        localRevision: Int = 12, serverRevision: Int = 12, baselineRevision: Int = 12,
        signature: String? = "accepted", acceptedSignature: String? = "accepted", payloadsMatch: Boolean = true) =
        LocalReportRetention.acknowledged(owner, localComplete, serverComplete, pending, refresh, canReceive,
            localRevision, serverRevision, baselineRevision, signature, acceptedSignature, payloadsMatch)

    @Test fun completedIncidentsAreEligibleNowWhileRelocationsKeepMonthEndRule() {
        assertTrue(LocalReportRetention.eligible("Sự cố", "2026-10", "2026-10", true, true))
        assertTrue(LocalReportRetention.eligible("Sự cố", "", "2026-10", true, true))
        assertFalse(LocalReportRetention.eligible("Sự cố", "2026-10", "2026-10", false, true))
        assertFalse(LocalReportRetention.eligible("Sự cố", "2026-10", "2026-10", true, false))
        assertFalse(LocalReportRetention.eligible("Di dời", "2026-10", "2026-10", true, true))
        assertTrue(LocalReportRetention.eligible("Di dời", "2026-09", "2026-10", true, true))
    }

    @Test fun onlyExactOwnerConfirmedClosedAndFullyAcknowledgedSnapshotsCanLeavePhone() {
        assertTrue(acknowledged())
        assertFalse(acknowledged(owner = false))
        assertFalse(acknowledged(localComplete = false))
        assertFalse(acknowledged(serverComplete = false))
        assertFalse(acknowledged(pending = true))
        assertFalse(acknowledged(refresh = true))
        assertFalse(acknowledged(canReceive = false))
        assertFalse(acknowledged(signature = "new edit"))
        assertFalse(acknowledged(acceptedSignature = null))
        assertFalse(acknowledged(signature = "", acceptedSignature = ""))
        assertFalse(acknowledged(payloadsMatch = false))
    }

    @Test fun serverChangesOrMissingAcknowledgementRequireReceiveBeforeRemoval() {
        assertFalse(acknowledged(serverRevision = 13)) // OCR/admin changes still need to be received.
        assertFalse(acknowledged(serverRevision = 11))
        assertFalse(acknowledged(baselineRevision = 11))
        assertFalse(acknowledged(localRevision = 0, serverRevision = 0, baselineRevision = 0))
    }

    @Test fun fullReportAndOriginalImagesMustMatchBothAcknowledgedPayloads() {
        val asset = "a".repeat(32)
        val original = "b".repeat(32)
        fun payload() = JSONObject("""{"module":"incident","completed":true,"photos":[],"report":{"solution":"fixed","groups":[{"images":[{"file":"$asset","originalFile":"$original","width":100}]}]}}""")
        val local = payload(); val accepted = payload(); val server = payload()
        assertTrue(LocalReportRetention.payloadsMatch(local, accepted, server, payload()))
        val different = payload().apply { getJSONObject("report").put("solution", "new unsent edit") }
        assertFalse(LocalReportRetention.payloadsMatch(different, accepted, server, payload()))
        val missing = payload().apply { getJSONObject("report").getJSONArray("groups").getJSONObject(0).getJSONArray("images").getJSONObject(0).remove("originalFile") }
        assertFalse(LocalReportRetention.payloadsMatch(local, accepted, missing, payload()))
        val localFile = payload().apply { getJSONObject("report").getJSONArray("groups").getJSONObject(0).getJSONArray("images").getJSONObject(0).put("file", "/photos/not-uploaded.jpg") }
        assertFalse(LocalReportRetention.payloadsMatch(localFile, localFile, server, payload()))
        val reordered = JSONObject("""{"report":{"groups":[{"images":[{"width":100.0,"originalFile":"$original","file":"$asset"}]}],"solution":"fixed"},"photos":[],"completed":true,"module":"incident"}""")
        assertTrue(LocalReportRetention.payloadsMatch(local, reordered, server, reordered))
    }

    @Test fun reopenedServerReportRestoresButCompletedOcrRevisionDoesNot() {
        assertTrue(LocalReportRetention.shouldRestore(true, false))
        assertFalse(LocalReportRetention.shouldRestore(true, true))
        assertFalse(acknowledged(serverComplete = false))
    }

    @Test fun sameRevisionServerGpsEnrichmentMustBeReceivedBeforeRemoval() {
        val asset = "a".repeat(32)
        val uploaded = JSONObject("""{"completed":true,"report":{"groups":[{"images":[{"file":"$asset"}]}]}}""")
        val enriched = JSONObject(uploaded.toString()).apply {
            getJSONObject("report").put("closureLocation", "Tọa độ MX: 12.700209, 108.327232")
                .getJSONArray("groups").getJSONObject(0).put("pairNotes", org.json.JSONArray().put("Tọa độ MX: 12.700209, 108.327232"))
                .getJSONArray("images").getJSONObject(0).put("gps", JSONObject().put("lat", 12.700209).put("lon", 108.327232))
                .put("mxCoordinateDescriptionApplied", true)
        }
        assertTrue(LocalReportRetention.localPayloadAcknowledged(uploaded, uploaded))
        assertTrue(LocalReportRetention.needsServerRefresh(uploaded, uploaded, enriched, uploaded))
        assertFalse(LocalReportRetention.payloadsMatch(uploaded, uploaded, enriched, uploaded))
        // Ordinary receive persists both new localWire and server wire. The next
        // cycle can retire the copy without dropping any enriched fields.
        assertFalse(LocalReportRetention.needsServerRefresh(enriched, enriched, enriched, enriched))
        assertTrue(LocalReportRetention.payloadsMatch(enriched, enriched, enriched, enriched))
    }

    @Test fun serverEnrichmentCannotReplaceUnacknowledgedLocalEditsOrAssets() {
        val uploaded = JSONObject("""{"completed":true,"report":{"solution":"saved","groups":[]}}""")
        val enriched = JSONObject(uploaded.toString()).apply { getJSONObject("report").put("closureLocation", "GPS") }
        val dirty = JSONObject(uploaded.toString()).apply { getJSONObject("report").put("solution", "new edit") }
        assertFalse(LocalReportRetention.needsServerRefresh(dirty, uploaded, enriched, uploaded))
        val notUploaded = JSONObject("""{"file":"/photos/new.jpg"}""")
        assertFalse(LocalReportRetention.needsServerRefresh(notUploaded, notUploaded, enriched, uploaded))
        assertFalse(LocalReportRetention.needsServerRefresh(uploaded, uploaded, notUploaded, uploaded))
    }
    @Test fun onlyEarlierValidMonthsCanBeMovedOffThePhone() {
        assertTrue(LocalReportRetention.isPastMonth("2026-09", "2026-10"))
        assertTrue(LocalReportRetention.isPastMonth("2025-12", "2026-01"))
        assertFalse(LocalReportRetention.isPastMonth("2026-10", "2026-10"))
        assertFalse(LocalReportRetention.isPastMonth("2026-11", "2026-10"))
        assertFalse(LocalReportRetention.isPastMonth("", "2026-10"))
        assertFalse(LocalReportRetention.isPastMonth("2026-13", "2026-10"))
    }

    @Test fun aPastReportIsRemovedOnlyWhenBothPhoneAndServerMarkItComplete() {
        assertTrue(LocalReportRetention.shouldRemove("2026-09", "2026-10", true, true))
        assertFalse(LocalReportRetention.shouldRemove("2026-09", "2026-10", false, true))
        assertFalse(LocalReportRetention.shouldRemove("2026-09", "2026-10", true, false))
        assertFalse(LocalReportRetention.shouldRemove("2026-10", "2026-10", true, true))
    }

    @Test fun serverReopenedReportsAreEligibleForDownloadToTheAuthorPhone() {
        assertTrue(LocalReportRetention.shouldRestore(serverOnly = true, serverCompleted = false))
        assertFalse(LocalReportRetention.shouldRestore(serverOnly = false, serverCompleted = false))
        assertFalse(LocalReportRetention.shouldRestore(serverOnly = true, serverCompleted = true))
    }
}

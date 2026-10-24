package vn.vltelecom

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PhotoLibraryAccessTest {
    private val owner = "1".repeat(32)
    private val other = "2".repeat(32)
    private val photo = LibraryPhoto("a".repeat(32), "Ảnh", "Người chụp", "user", 1L, ownerId = owner)

    @Test fun ownerAndAdminCanManageWhileSupportCannotManageColleaguePhoto() {
        assertTrue(PhotoLibrary.canManagePhoto(photo, owner, "staff"))
        assertTrue(PhotoLibrary.canManagePhoto(photo, owner, "dispatcher"))
        assertTrue(PhotoLibrary.canManagePhoto(photo, other, "admin"))
        assertFalse(PhotoLibrary.canManagePhoto(photo, other, "staff"))
        assertFalse(PhotoLibrary.canManagePhoto(photo, other, "dispatcher"))
    }

    @Test fun ViewerAndUnknownRolesCannotDeleteEvenTheirOwnPhoto() {
        assertFalse(PhotoLibrary.canManagePhoto(photo, owner, "viewer"))
        assertFalse(PhotoLibrary.canManagePhoto(photo, owner, ""))
        assertFalse(PhotoLibrary.canManagePhoto(photo, owner, "unknown"))
        assertFalse(PhotoLibrary.canManagePhoto(photo, "", "admin"))
    }

    @Test fun legacyPhotoWithoutOwnerIsReadableButNotOwnedForDeletion() {
        val rows = PhotoLibrary.parseServerPhotos(JSONObject().put("photos", JSONArray().put(
            JSONObject().put("id", photo.id).put("name", "Ảnh cũ").put("captured_at", 123456L))))
        assertEquals(1, rows.size)
        assertEquals("", rows.single().ownerId)
        assertEquals(0L, rows.single().deletedAt)
        assertFalse(PhotoLibrary.canManagePhoto(rows.single(), owner, "staff"))
        assertFalse(PhotoLibrary.canManagePhoto(rows.single(), owner, "dispatcher"))
    }

    @Test fun trashMetadataPreservesOwnerAndTimestampWithoutChangingReportFields() {
        val row = JSONObject().put("id", photo.id).put("name", "Ảnh Camera")
            .put("owner", owner).put("username", "user").put("photographer", "Người chụp")
            .put("captured_at", 1791000000123L).put("deleted_at", 1791001000L)
            .put("details", JSONObject().put("locationName", "Tuyến 1").put("note", "Trụ 10")
                .put("lat", 12.5).put("lon", 108.1).put("accuracy", 4.25))
        val parsed = PhotoLibrary.parseServerPhotos(JSONObject().put("photos", JSONArray().put(row))).single()
        assertEquals(owner, parsed.ownerId)
        assertEquals(1791001000L, parsed.deletedAt)
        assertEquals(1791000000123L, parsed.capturedAt)
        assertEquals("Trụ 10", parsed.note)
        assertEquals("Tuyến 1", parsed.locationName)
        assertEquals(12.5, parsed.lat, 0.00001)
        assertEquals(108.1, parsed.lon, 0.00001)
        assertEquals(4.25f, parsed.accuracy, 0.00001f)
    }

    @Test fun malformedServerIdsAreNeverSelectableAndOldReportConstructorRemainsValid() {
        val rows = JSONArray().put(JSONObject().put("id", "../another-user"))
            .put(JSONObject().put("id", "12345678-1234-1234-1234-123456789abc"))
            .put(JSONObject().put("id", photo.id))
        assertEquals(listOf(photo.id), PhotoLibrary.parseServerPhotos(JSONObject().put("photos", rows)).map { it.id })
        val localReport = LibraryPhoto("uuid-local", "Photo", "Author", "user", 1L, "Place", 12.0, 108.0, 2f, "Note")
        assertEquals("Note", localReport.note)
        assertEquals("", localReport.ownerId)
        assertEquals(0L, localReport.deletedAt)
    }
}

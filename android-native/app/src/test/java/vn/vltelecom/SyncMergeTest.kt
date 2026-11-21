package vn.vltelecom

import org.junit.Assert.*
import org.junit.Test

class SyncMergeTest {
    private fun photo(id: String, seq: Int, note: String = "", file: String = "rendered-$id") =
        mapOf("originalFile" to id, "file" to file, "seq" to seq, "note" to note)

    private fun folder(vararg photos: Map<String, Any>) = mapOf("photos" to photos.toList())

    private fun merged(base: Any?, local: Any?, remote: Any?): Any? {
        val result = SyncMerge.merge(base, local, remote)
        assertTrue(result.conflicts.toString(), result.successful)
        return result.value
    }

    @Test fun disjointNestedFieldsMerge() {
        val base = mapOf("report" to mapOf("note" to "old", "team" to "one"))
        val local = mapOf("report" to mapOf("note" to "local", "team" to "one"))
        val remote = mapOf("report" to mapOf("note" to "old", "team" to "two"))
        assertEquals(mapOf("report" to mapOf("note" to "local", "team" to "two")), merged(base, local, remote))
    }

    @Test fun divergentFieldReturnsNoPartialPayload() {
        val result = SyncMerge.merge(mapOf("note" to "old"), mapOf("note" to "local"), mapOf("note" to "remote"))
        assertFalse(result.successful)
        assertNull(result.value)
        assertEquals(listOf("$.note"), result.conflicts)
    }

    @Test fun deletionIsDistinctFromExplicitNull() {
        val base = mapOf("note" to "old", "keep" to true)
        val deletion = mapOf("keep" to true)
        val clearing = mapOf("note" to null, "keep" to true)
        assertEquals(deletion, merged(base, deletion, base))
        assertEquals(clearing, merged(base, base, clearing))
        assertFalse(SyncMerge.merge(base, deletion, clearing).successful)
        assertTrue(SyncMerge.merge("old", null, "old").successful)
    }

    @Test fun localAndRemotePhotoDeletionsPropagateWithoutResurrection() {
        val a = photo("a", 1)
        val b = photo("b", 2)
        val c = photo("c", 3)
        val base = folder(a, b, c)
        assertEquals(folder(c), merged(base, folder(b, c), folder(a, c)))
    }

    @Test fun deleteVersusModifiedPhotoConflictsInEitherDirection() {
        val base = folder(photo("a", 1))
        val edited = folder(photo("a", 1, note = "server change"))
        assertFalse(SyncMerge.merge(base, folder(), edited).successful)
        assertFalse(SyncMerge.merge(base, edited, folder()).successful)
    }

    @Test fun concurrentDifferentPhotoAdditionsAndEditsSurvive() {
        val a = photo("a", 1)
        val localA = photo("a", 1, note = "phone")
        val remoteA = photo("a", 1, file = "server-render")
        val result = merged(folder(a), folder(localA, photo("b", 2)), folder(remoteA, photo("c", 3))) as Map<*, *>
        val photos = result["photos"] as List<*>
        assertEquals(3, photos.size)
        assertEquals(photo("a", 1, note = "phone", file = "server-render"), photos.first())
        assertEquals(setOf("a", "b", "c"), photos.map { (it as Map<*, *>)["originalFile"] }.toSet())
    }

    @Test fun sameSequenceForDifferentAddedPhotosConflicts() {
        val result = SyncMerge.merge(folder(), folder(photo("a", 1)), folder(photo("b", 1)))
        assertFalse(result.successful)
        assertTrue(result.conflicts.any { it.contains("photos.seq=") })
    }

    @Test fun duplicateSequenceIsRejectedEvenOnOneSideOnly() {
        assertFalse(SyncMerge.merge(folder(), folder(photo("a", 1), photo("b", 1)), folder()).successful)
    }

    @Test fun originalPhotoIdentitySurvivesRenderedFileReplacement() {
        val base = folder(photo("raw", 1))
        val local = folder(photo("raw", 1, file = "new-overlay"))
        val remote = folder(photo("raw", 1, note = "admin"))
        assertEquals(folder(photo("raw", 1, note = "admin", file = "new-overlay")), merged(base, local, remote))
    }

    @Test fun groupsAndImagesMergeByUniqueNamesAndAssets() {
        fun report(images: List<Any?>, note: String) = mapOf("groups" to listOf(mapOf("name" to "Before", "images" to images, "note" to note)))
        val a = mapOf("file" to "a", "note" to "old")
        val b = mapOf("file" to "b", "note" to "new")
        val expected = report(listOf(b), "admin")
        assertEquals(expected, merged(report(listOf(a), "old"), report(listOf(a, b), "old"), report(emptyList(), "admin")))
    }

    @Test fun duplicateImageIdentityAndUnknownArraysAreAtomic() {
        val a = mapOf("file" to "a")
        assertFalse(SyncMerge.merge(mapOf("images" to listOf(a, a)), mapOf("images" to listOf(a)), mapOf("images" to listOf(a, a, mapOf("file" to "b")))).successful)
        assertFalse(SyncMerge.merge(listOf(1), listOf(1, 2), listOf(1, 3)).successful)
        assertEquals(listOf(1, 2), merged(listOf(1), listOf(1, 2), listOf(1)))
    }

    @Test fun newDisjointObjectFieldsAndNumberRepresentationsMerge() {
        assertEquals(mapOf("extra" to mapOf("a" to 1, "b" to 2)),
            merged(emptyMap<String, Any?>(), mapOf("extra" to mapOf("a" to 1)), mapOf("extra" to mapOf("b" to 2))))
        assertEquals(mapOf("n" to 2), merged(mapOf("n" to 1), mapOf("n" to 1.0), mapOf("n" to 2)))
    }

    @Test fun simultaneousDifferentReorderingRequiresReview() {
        val a = photo("a", 1)
        val b = photo("b", 2)
        val c = photo("c", 3)
        val result = SyncMerge.merge(folder(a, b, c), folder(b, a, c), folder(a, c, b))
        assertFalse(result.successful)
        assertEquals(listOf("$.photos.order"), result.conflicts)
    }

    @Test fun localReorderAndRemoteFieldEditBothSurvive() {
        val a = photo("a", 1)
        val b = photo("b", 2)
        assertEquals(folder(b, photo("a", 1, note = "server")),
            merged(folder(a, b), folder(b, a), folder(photo("a", 1, note = "server"), b)))
    }

    private fun imageReport(images: List<Map<String, Any>>, notes: List<String>) =
        mapOf("report" to mapOf("groups" to listOf(mapOf("name" to "MX", "images" to images, "pairNotes" to notes))))

    @Test fun ownerDeletionAndContributorAdditionCannotHideOrReassignManualRowNotes() {
        val a = mapOf("file" to "a", "caption" to "A")
        val b = mapOf("file" to "b", "caption" to "B")
        val c = mapOf("file" to "c", "caption" to "C")
        val base = imageReport(listOf(a, b), listOf("Ghi chú hàng A/B"))
        val owner = imageReport(listOf(b), listOf("Ghi chú hàng A/B"))
        val contributor = imageReport(listOf(a, b, c), listOf("Ghi chú hàng A/B", "Ghi chú riêng ảnh C"))
        for ((local, remote) in listOf(owner to contributor, contributor to owner)) {
            val result = SyncMerge.merge(base, local, remote)
            assertFalse(result.successful)
            assertNull(result.value)
            assertTrue(result.conflicts.any { it.endsWith(".pairNotes") })
        }
    }

    @Test fun independentImageDeletionsWithPositionalNotesRequireReview() {
        val images = listOf("a", "b", "c", "d").map { mapOf("file" to it, "caption" to it) }
        val base = imageReport(images, listOf("AB", "CD"))
        val local = imageReport(images.drop(1), listOf("AB\nCD", "CD"))
        val remote = imageReport(images.dropLast(1), listOf("AB", "CD"))
        val result = SyncMerge.merge(base, local, remote)
        assertFalse(result.successful)
        assertNull(result.value)
        assertTrue(result.conflicts.any { it.endsWith(".pairNotes") })
    }

    @Test fun deletionKeepsItsRowsWhenOtherSideOnlyEditsSurvivingPhotoCaption() {
        val a = mapOf("file" to "a", "caption" to "A")
        val b = mapOf("file" to "b", "caption" to "B")
        val changedB = b + ("caption" to "Thành viên đã sửa B")
        val notes = listOf("Ghi chú người dùng\nGhi chú người dùng")
        assertEquals(imageReport(listOf(changedB), notes), merged(
            imageReport(listOf(a, b), notes), imageReport(listOf(b), notes), imageReport(listOf(a, changedB), notes)))
    }

    @Test fun combinedLayoutWithoutRowNotesStillMergesAndDoesNotResurrectDeletion() {
        val a = mapOf("file" to "a", "caption" to "A")
        val b = mapOf("file" to "b", "caption" to "B")
        val c = mapOf("file" to "c", "caption" to "C")
        assertEquals(imageReport(listOf(b, c), emptyList()), merged(
            imageReport(listOf(a, b), emptyList()), imageReport(listOf(b), emptyList()), imageReport(listOf(a, b, c), emptyList())))
    }

    @Test fun deletedImageAgainstEditedCaptionStillConflictsWithRowNotes() {
        val a = mapOf("file" to "a", "caption" to "A")
        val base = imageReport(listOf(a), listOf("Ghi chú A"))
        val result = SyncMerge.merge(base, imageReport(emptyList(), emptyList()),
            imageReport(listOf(a + ("caption" to "Sửa mới")), listOf("Ghi chú A")))
        assertFalse(result.successful)
        assertNull(result.value)
    }
}

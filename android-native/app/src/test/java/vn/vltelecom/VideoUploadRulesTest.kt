package vn.vltelecom

import org.junit.Assert.*
import org.junit.Test

class VideoUploadRulesTest {
    @Test fun chunksAreBoundedAndLastChunkUsesRemainingBytes() {
        val total = 2L * VideoUploadRules.CHUNK_BYTES + 123
        assertEquals(VideoUploadRules.CHUNK_BYTES, VideoUploadRules.nextChunk(total, 0))
        assertEquals(123, VideoUploadRules.nextChunk(total, 2L * VideoUploadRules.CHUNK_BYTES))
        assertEquals(0, VideoUploadRules.nextChunk(total, total))
    }
    @Test fun resumeAllowsUnalignedDurableServerOffset() {
        assertEquals(12345L, VideoUploadRules.resumeOffset(20000, 12345))
    }
    @Test fun invalidResumeOffsetsAndOversizedFilesAreRejected() {
        for ((total, offset) in listOf(0L to 0L, 100L to -1L, 100L to 101L, (VideoUploadRules.MAX_BYTES + 1) to 0L)) {
            assertThrows(IllegalArgumentException::class.java) { VideoUploadRules.resumeOffset(total, offset) }
        }
    }
    @Test fun onlyExactChunkAcknowledgementAdvancesUpload() {
        assertEquals(612L, VideoUploadRules.acknowledged(1000, 100, 512, 612))
        for (ack in listOf(100L, 611L, 613L, 1001L)) {
            assertThrows(IllegalArgumentException::class.java) { VideoUploadRules.acknowledged(1000, 100, 512, ack) }
        }
    }
    @Test fun completionNeedsFullLengthExplicitSuccessAndValidServerId() {
        val id = "a".repeat(32)
        assertTrue(VideoUploadRules.complete(2048, 2048, true, id))
        assertFalse(VideoUploadRules.complete(2048, 2047, true, id))
        assertFalse(VideoUploadRules.complete(2048, 2048, false, id))
        assertFalse(VideoUploadRules.complete(2048, 2048, true, ""))
    }
    @Test fun stableUploadIdRejectsPathsAndMalformedIds() {
        assertTrue(VideoUploadRules.validId("f3112449-e3b0-4e52-86e4-0926d35d475a"))
        assertFalse(VideoUploadRules.validId("../elsewhere"))
        assertFalse(VideoUploadRules.validId("f3112449-e3b0-4e52-86e4-0926d35d475a.json"))
    }
}

package vn.vltelecom

/** Pure bounds/acknowledgement checks shared by resumable video uploads and unit tests. */
object VideoUploadRules {
    const val MAX_BYTES = 64L * 1024 * 1024
    const val CHUNK_BYTES = 1024 * 1024
    fun validId(value: String) = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}").matches(value)
    fun resumeOffset(total: Long, offset: Long): Long {
        require(total in 1..MAX_BYTES && offset in 0..total) { "Vị trí tiếp tục video không hợp lệ." }
        return offset
    }
    fun nextChunk(total: Long, offset: Long): Int = minOf(CHUNK_BYTES.toLong(), total - resumeOffset(total, offset)).toInt()
    fun acknowledged(total: Long, previous: Long, sent: Int, acknowledged: Long): Long {
        require(sent in 1..CHUNK_BYTES && previous + sent <= total && previous >= 0 && acknowledged == previous + sent) {
            "Server chưa xác nhận đầy đủ phần video vừa gửi."
        }
        return resumeOffset(total, acknowledged)
    }
    fun complete(total: Long, offset: Long, complete: Boolean, serverId: String): Boolean =
        total in 1..MAX_BYTES && offset == total && complete && Regex("[a-f0-9]{32}").matches(serverId)
}

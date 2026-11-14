package vn.vltelecom

import java.io.File
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException

/** The caller supplies a directory inside the signed-in account's private storage. */
internal class MxLocalCache(private val directory: File) {
    data class Entry(val point: MxLocalParser.Point?, val source: String)
    companion object {
        const val VERSION = "android-mx-local-2"
        const val MAX_FILE_BYTES = 64L * 1024 * 1024
        fun sha256(file: File): String {
            require(file.isFile && file.length() in 1..MAX_FILE_BYTES) { "Ảnh trống hoặc lớn hơn 64 MB." }
            val digest = MessageDigest.getInstance("SHA-256")
            var count = 0L
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(32768)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    count += n
                    require(count <= MAX_FILE_BYTES) { "Ảnh lớn hơn 64 MB." }
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
    }
    private fun file(hash: String): File {
        require(hash.matches(Regex("[a-f0-9]{64}")))
        return File(directory, "$VERSION-$hash.txt")
    }
    fun get(hash: String): Entry? = runCatching {
        val target = file(hash)
        if (!target.isFile || target.length() > 512) return null
        val fields = target.readText().split('\n')
        if (fields.firstOrNull() != VERSION) return null
        if (fields.getOrNull(1) == "unreadable" && fields.size == 2) return Entry(null, "")
        if (fields.size != 5 || fields[1] != "readable" || fields[4] !in listOf("EXIF", "ocr")) return null
        val lat = fields[2].toDoubleOrNull() ?: return null
        val lon = fields[3].toDoubleOrNull() ?: return null
        if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        Entry(MxLocalParser.Point(lat, lon), fields[4])
    }.getOrNull()

    fun put(hash: String, entry: Entry) {
        directory.mkdirs()
        val target = file(hash)
        val content = entry.point?.let { "$VERSION\nreadable\n${it.lat}\n${it.lon}\n${entry.source}" } ?: "$VERSION\nunreadable"
        val temp = File.createTempFile("mx-result-", ".tmp", directory)
        try {
            temp.outputStream().use { output -> output.write(content.toByteArray(Charsets.UTF_8)); output.fd.sync() }
            try {
                Files.move(temp.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING)
            }
        } finally { temp.delete() }
    }
}

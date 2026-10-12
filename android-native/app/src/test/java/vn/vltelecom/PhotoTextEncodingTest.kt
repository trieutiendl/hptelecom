package vn.vltelecom
import org.junit.Assert.*
import org.junit.Test

class PhotoTextEncodingTest {
    @Test fun exifAsciiRoundTripKeepsVietnameseAndEmoji(){
        val text="Ngày chụp: 19/09/2026 • Ghi chú: Măng xông Đắk Lắk 📷"
        val encoded=PhotoTextEncoding.asciiJson(text)
        assertTrue(encoded.all{it.code<128})
        val decoded=Regex("\\\\u([0-9a-f]{4})").replace(encoded){it.groupValues[1].toInt(16).toChar().toString()}
        assertEquals(text,decoded)
    }
    @Test fun restoresOnlyKnownLabels(){
        assertEquals("Ngày chụp: 19/09/2026",PhotoTextEncoding.restoreLabel("Ng?y ch?p: 19/09/2026"))
        assertEquals("Ghi chú: Đ?ng?",PhotoTextEncoding.restoreLabel("Ghi ch?: Đ?ng?"))
        assertEquals("Tên: Đ?ng?",PhotoTextEncoding.restoreLabel("Tên: Đ?ng?"))
    }
}

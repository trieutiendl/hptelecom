package vn.vltelecom

import org.junit.Assert.*
import org.junit.Test

class CameraStampTest {
    @Test fun wideLogoUsesTwentyPercentWidthWithoutDistortion() {
        val size = CameraStampLayout.logoSize(1200, 1600, 800, 200)
        assertEquals(240f, size.width, .001f)
        assertEquals(60f, size.height, .001f)
    }

    @Test fun tallLogoIsLimitedByPhotoHeight() {
        val size = CameraStampLayout.logoSize(1200, 1600, 100, 2000)
        assertEquals(16f, size.width, .001f)
        assertEquals(320f, size.height, .001f)
    }

    @Test fun landscapeAndSmallPhotosKeepLogoInsideBounds() {
        for ((width, height) in listOf(1600 to 1200, 320 to 240, 240 to 320)) {
            for ((logoWidth, logoHeight) in listOf(4 to 1, 1 to 4, 1 to 1)) {
                val size = CameraStampLayout.logoSize(width, height, logoWidth, logoHeight)
                assertTrue(size.width <= width * .20f + .001f)
                assertTrue(size.height <= height * .20f + .001f)
                assertEquals(logoWidth.toFloat() / logoHeight, size.width / size.height, .001f)
            }
        }
    }

    @Test fun rejectsMissingOrInvalidGpsButAllowsTheEquator() {
        assertFalse(CameraStampLayout.validCoordinates(null, 108.0))
        assertFalse(CameraStampLayout.validCoordinates(Double.NaN, 108.0))
        assertFalse(CameraStampLayout.validCoordinates(91.0, 108.0))
        assertFalse(CameraStampLayout.validCoordinates(12.0, Double.POSITIVE_INFINITY))
        assertTrue(CameraStampLayout.validCoordinates(0.0, 0.0))
        assertTrue(CameraStampLayout.validCoordinates(-90.0, -180.0))
    }

    private fun sample() = CameraStamp.Snapshot(CameraStamp.Settings(note = "Cáp đã được kiểm tra"),
        1_700_000_000_000L, 12.5, 108.25, 444.4, "Ea Na, Đắk Lắk", "Nguyễn Văn A",
        "Asia/Ho_Chi_Minh", "frozen-logo-png")

    @Test fun metadataRoundTripKeepsFrozenLogoAndAllCaptureFields() {
        val value = sample()
        assertEquals(value, CameraStamp.decode(CameraStamp.encode(value)))
        assertEquals(value.copy(settings = CameraStamp.Settings(false, false, false, false, false, false, ""),
            latitude = null, longitude = null, altitudeMeters = null, logoPng = ""),
            CameraStamp.decode(CameraStamp.encode(value.copy(settings = CameraStamp.Settings(false, false, false,
                false, false, false, ""), latitude = null, longitude = null, altitudeMeters = null, logoPng = ""))))
    }

    @Test fun laterTemplateChangesCannotRewriteCapturedSnapshot() {
        val captured = sample()
        val later = captured.settings.copy(logo = false, coordinates = false, note = "Ghi chú mới")
        val another = captured.copy(settings = later, logoPng = "another-logo-png")
        assertTrue(captured.settings.logo)
        assertEquals("Cáp đã được kiểm tra", captured.settings.note)
        assertEquals("frozen-logo-png", captured.logoPng)
        assertNotEquals(captured, another)
    }

    @Test fun unavailableLocationNeverProducesFakeCoordinatesOrAltitude() {
        val missing = sample().copy(latitude = null, longitude = null, altitudeMeters = null, address = "")
        val text = CameraStamp.lines(missing)
        assertFalse(text.any { it.startsWith("Tọa độ:") || it.startsWith("Độ cao:") })
        assertTrue(text.any { it.contains("Nguyễn Văn A") })
        assertTrue(text.any { it.contains("15/11/2023") })
    }

    @Test fun defaultDateTimeFormatKeepsFullTimestampAndDate() {
        assertEquals(CameraStamp.DateTimeFormat.DATE_TIME_SECONDS, CameraStamp.Settings().dateTimeFormat)
        assertEquals("05:13:20  |  15/11/2023", CameraStamp.lines(sample()).first())
    }

    @Test fun allDateTimeFormatsUseTheCapturedTimeAndTimeZone() {
        val expected = mapOf(
            CameraStamp.DateTimeFormat.DATE_TIME_SECONDS to "05:13:20  |  15/11/2023",
            CameraStamp.DateTimeFormat.DATE_TIME_MINUTES to "05:13  |  15/11/2023",
            CameraStamp.DateTimeFormat.DATE_ONLY to "15/11/2023",
            CameraStamp.DateTimeFormat.TIME_SECONDS to "05:13:20",
            CameraStamp.DateTimeFormat.TIME_MINUTES to "05:13"
        )
        val captured = sample()
        expected.forEach { (format, text) ->
            val formatted = captured.copy(settings = captured.settings.copy(dateTimeFormat = format))
            assertEquals(format.name, text, CameraStamp.lines(formatted).first())
        }
    }

    @Test fun disablingDateHidesEveryDateTimeFormat() {
        val captured = sample()
        CameraStamp.DateTimeFormat.values().forEach { format ->
            val onlyDate = captured.settings.copy(logo = false, date = false, location = false,
                coordinates = false, altitude = false, author = false, note = "", dateTimeFormat = format)
            assertTrue(format.name, CameraStamp.lines(captured.copy(settings = onlyDate)).isEmpty())
        }
    }

    @Test fun eachDateTimeFormatSurvivesMetadataRoundTrip() {
        val captured = sample()
        CameraStamp.DateTimeFormat.values().forEach { format ->
            val formatted = captured.copy(settings = captured.settings.copy(dateTimeFormat = format))
            val encoded = CameraStamp.encode(formatted)
            assertEquals(format.name, encoded.getJSONObject("settings").getString("dateTimeFormat"))
            assertEquals(formatted, CameraStamp.decode(encoded))
        }
    }

    @Test fun changingDateTimePreferenceDoesNotChangeAnEarlierPhoto() {
        val captured = sample()
        val laterSettings = captured.settings.copy(dateTimeFormat = CameraStamp.DateTimeFormat.DATE_ONLY)
        val laterPhoto = captured.copy(settings = laterSettings)
        assertEquals("15/11/2023", CameraStamp.lines(laterPhoto).first())
        assertEquals(CameraStamp.DateTimeFormat.DATE_TIME_SECONDS, captured.settings.dateTimeFormat)
        assertEquals("05:13:20  |  15/11/2023", CameraStamp.lines(captured).first())
        assertEquals(captured, CameraStamp.decode(CameraStamp.encode(captured)))
    }

    @Test fun schemaOneWithoutDateTimeFormatKeepsItsOriginalAppearance() {
        val original = sample()
        val legacy = CameraStamp.encode(original)
        legacy.put("schema", 1)
        legacy.getJSONObject("settings").remove("dateTimeFormat")
        val restored = CameraStamp.decode(legacy)
        assertEquals(original, restored)
        assertEquals("05:13:20  |  15/11/2023", CameraStamp.lines(restored).first())
    }

    @Test fun unknownStoredDateTimeFormatFallsBackToFullTimestampAndDate() {
        val original = sample()
        for (unknown in listOf("", "FUTURE_FORMAT", "date_only")) {
            val encoded = CameraStamp.encode(original)
            encoded.getJSONObject("settings").put("dateTimeFormat", unknown)
            val restored = CameraStamp.decode(encoded)
            assertEquals(unknown, CameraStamp.DateTimeFormat.DATE_TIME_SECONDS, restored.settings.dateTimeFormat)
            assertEquals("05:13:20  |  15/11/2023", CameraStamp.lines(restored).first())
        }
    }
}

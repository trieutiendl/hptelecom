package vn.vltelecom

import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.ResolverStyle
import java.util.Locale

/** Incident order follows when the incident was received, not its title or last edit. */
internal object IncidentReceivedOrder {
    private val vietnam = ZoneId.of("Asia/Ho_Chi_Minh")
    private val dateFormats = listOf("d/M/uuuu", "d-M-uuuu", "uuuu-MM-dd").map {
        DateTimeFormatter.ofPattern(it, Locale.ROOT).withResolverStyle(ResolverStyle.STRICT)
    }
    private val timeFormats = listOf("d/M/uuuu H:m", "d/M/uuuu H:m:s", "d-M-uuuu H:m", "d-M-uuuu H:m:s", "uuuu-MM-dd H:m", "uuuu-MM-dd H:m:s").map {
        DateTimeFormatter.ofPattern(it, Locale.ROOT).withResolverStyle(ResolverStyle.STRICT)
    }
    private val amPmFormats = listOf("d/M/uuuu h:m a", "d/M/uuuu h:m:s a").map {
        DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(it).toFormatter(Locale.US).withResolverStyle(ResolverStyle.STRICT)
    }

    fun timestamp(value: String): LocalDateTime? {
        val text = value.trim().replace(Regex("\\s+"), " ")
        if (text.isEmpty()) return null
        val offsetText = if (text.matches(Regex("\\d{4}-\\d{2}-\\d{2} .+"))) text.replaceFirst(' ', 'T') else text
        return runCatching { OffsetDateTime.parse(offsetText, DateTimeFormatter.ISO_OFFSET_DATE_TIME).atZoneSameInstant(vietnam).toLocalDateTime() }.getOrNull()
            ?: timeFormats.firstNotNullOfOrNull { runCatching { LocalDateTime.parse(text, it) }.getOrNull() }
            ?: amPmFormats.firstNotNullOfOrNull { runCatching { LocalDateTime.parse(text, it) }.getOrNull() }
            ?: runCatching { LocalDateTime.parse(text, DateTimeFormatter.ISO_LOCAL_DATE_TIME) }.getOrNull()
            ?: dateFormats.firstNotNullOfOrNull { runCatching { LocalDate.parse(text, it).atStartOfDay() }.getOrNull() }
    }

    fun fromReport(value: String): LocalDateTime? = runCatching {
        val report = JSONObject(value)
        timestamp(report.optString("received")) ?: report.optJSONArray("routes")?.let { routes ->
            (0 until routes.length()).mapNotNull { index ->
                routes.optJSONObject(index)?.optJSONObject("report")?.let { timestamp(it.optString("received")) }
            }.minOrNull()
        }
    }.getOrNull()

    /** Missing/invalid dates follow valid ones; ties retain the existing order. */
    fun <T> sorted(rows: List<T>, received: (T) -> LocalDateTime?): List<T> = rows.withIndex()
        .map { Triple(it.value, received(it.value), it.index) }
        .sortedWith(compareBy<Triple<T, LocalDateTime?, Int>> { it.second == null }.thenBy { it.second }.thenBy { it.third })
        .map { it.first }
}

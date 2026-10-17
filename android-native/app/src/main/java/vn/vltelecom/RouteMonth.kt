package vn.vltelecom

import java.time.Instant
import java.time.ZoneId
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.ResolverStyle
import java.time.format.DateTimeFormatter

object RouteMonth {
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM").withZone(ZoneId.of("Asia/Ho_Chi_Minh"))

    fun key(createdSeconds: Long): String =
        if (createdSeconds <= 0) "" else runCatching { formatter.format(Instant.ofEpochSecond(createdSeconds)) }.getOrDefault("")

    fun reportKey(dateValue: String): String {
        val value = dateValue.trim().substringBefore('T').substringBefore(' ')
        if (value.isBlank()) return ""
        val date = listOf(
            DateTimeFormatter.ofPattern("d/M/uuuu").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("d-M-uuuu").withResolverStyle(ResolverStyle.STRICT),
        ).firstNotNullOfOrNull { formatter -> runCatching { LocalDate.parse(value, formatter) }.getOrNull() }
        return date?.let { YearMonth.from(it).format(DateTimeFormatter.ofPattern("uuuu-MM")) }.orEmpty()
    }
}

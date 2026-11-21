package vn.vltelecom

import java.text.Normalizer
import kotlin.math.abs

/** Parses complete GPS pairs, never pixel positions, dates, headings or a guessed missing axis. */
object MxLocalParser {
    data class Point(val lat: Double, val lon: Double)
    private data class Axis(val value: Double, val direction: Char, val range: IntRange)
    private const val NUMBER = "[+-]?\\d{1,3}(?:[.,]\\d{1,8})?"
    private val dms = Regex("(?<![\\d.+-])([+-]?\\d{1,3})\\s*°\\s*(\\d{1,2}(?:[.,]\\d+)?)\\s*'\\s*(?:(\\d{1,2}(?:[.,]\\d+)?)\\s*\")?\\s*([NSEW])(?![A-Z])")
    private val suffix = Regex("(?<![\\d.+-])($NUMBER)\\s*°?\\s*([NSEW])(?![A-Z])")
    private val prefixPair = Regex("(?<![A-Z])([NSEW])\\s*[:=]?\\s*($NUMBER)\\s*°?\\s*[,;|/ ]*\\s*([NSEW])\\s*[:=]?\\s*($NUMBER)\\s*°?(?![\\d.])")
    private val labels = Regex("(?:LAT(?:ITUDE)?|VI DO)\\s*[:=]?\\s*($NUMBER)\\s*°?\\s*[,;|/ ]*\\s*(?:LON(?:G(?:ITUDE)?)?|KINH DO)\\s*[:=]?\\s*($NUMBER)\\s*°?(?![\\d.])")
    // Unlabelled values need decimal precision; integers such as a date or a compass heading are not GPS.
    private val plain = Regex("(?<![\\d.+-])([+-]?\\d{1,2}\\.\\d{4,8})\\s*°?\\s*[,;| ]\\s*([+-]?\\d{1,3}\\.\\d{4,8})\\s*°?(?![\\d.])")

    fun candidates(text: String): List<Point> {
        val normalized = Normalizer.normalize(text.take(16000), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "").uppercase(java.util.Locale.ROOT)
            .replace('Đ', 'D').replace('−', '-').replace('–', '-')
            .replace('º', '°').replace('˚', '°').replace('′', '\'').replace('’', '\'')
            .replace('″', '"').replace('”', '"').replace('“', '"')
        val found = mutableListOf<Point>()
        // Pair wrapped labelled/directional values, but never combine arbitrary unlabelled numbers on different lines.
        val lines = normalized.lines()
        val directionalInputs = lines + lines.zipWithNext { a, b -> "$a $b" }
        directionalInputs.forEach { line ->
            val axes = mutableListOf<Axis>()
            val dmsRanges = mutableListOf<IntRange>()
            val prefixRanges = mutableListOf<IntRange>()
            prefixPair.findAll(line).forEach { match ->
                val a=number(match.groupValues[2])?:return@forEach
                val b=number(match.groupValues[4])?:return@forEach
                val da=match.groupValues[1][0];val db=match.groupValues[3][0]
                val va=signed(a,abs(a),da);val vb=signed(b,abs(b),db)
                if(da in "NS"&&db in "EW")point(va,vb)?.let(found::add)
                if(da in "EW"&&db in "NS")point(vb,va)?.let(found::add)
                prefixRanges+=match.range
            }
            dms.findAll(line).forEach { match ->
                dmsRanges += match.range
                val degrees = number(match.groupValues[1]) ?: return@forEach
                val minutes = number(match.groupValues[2]) ?: return@forEach
                val seconds = match.groupValues[3].takeIf { it.isNotBlank() }?.let(::number) ?: 0.0
                if (minutes !in 0.0..<60.0 || seconds !in 0.0..<60.0) return@forEach
                signed(degrees, abs(degrees) + minutes / 60.0 + seconds / 3600.0, match.groupValues[4][0])
                    ?.let { axes += Axis(it, match.groupValues[4][0], match.range) }
            }
            suffix.findAll(line).forEach { match ->
                if ((dmsRanges+prefixRanges).any { overlaps(it, match.range) }) return@forEach
                val value = number(match.groupValues[1]) ?: return@forEach
                signed(value, abs(value), match.groupValues[2][0])?.let { axes += Axis(it, match.groupValues[2][0], match.range) }
            }
            axes.sortedBy { it.range.first }.zipWithNext().forEach { (a, b) ->
                val gap = line.substring(a.range.last + 1, b.range.first)
                if (!gap.matches(Regex("[\\s,;|/]*"))) return@forEach
                if (a.direction in "NS" && b.direction in "EW") point(a.value, b.value)?.let(found::add)
                if (a.direction in "EW" && b.direction in "NS") point(b.value, a.value)?.let(found::add)
            }
            labels.findAll(line).forEach { match ->
                point(number(match.groupValues[1]), number(match.groupValues[2]))?.let(found::add)
            }
        }
        lines.forEach { line ->
            // Do not recover a plausible unsigned pair out of a malformed labelled/directional stamp.
            if (Regex("(?<![A-Z])[NSEW](?![A-Z])|LAT(?:ITUDE)?|LON(?:G(?:ITUDE)?)?|VI DO|KINH DO").containsMatchIn(line)) return@forEach
            plain.findAll(line).forEach { match -> point(number(match.groupValues[1]), number(match.groupValues[2]))?.let(found::add) }
        }
        return found.fold(mutableListOf()) { unique, point ->
            if (unique.none { same(it, point) }) unique.add(point)
            unique
        }
    }

    fun unique(text: String): Point? = candidates(text).singleOrNull()
    fun same(a: Point, b: Point) = abs(a.lat - b.lat) < 0.000001 && abs(a.lon - b.lon) < 0.000001
    private fun number(value: String) = value.replace(',', '.').toDoubleOrNull()
    private fun overlaps(a: IntRange, b: IntRange) = a.first <= b.last && b.first <= a.last
    private fun signed(original: Double, magnitude: Double, direction: Char): Double? {
        if (original < 0 && direction in "NE") return null
        return if (direction in "SW") -magnitude else magnitude
    }
    private fun point(lat: Double?, lon: Double?): Point? =
        if (lat != null && lon != null && lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0) Point(lat, lon) else null
}

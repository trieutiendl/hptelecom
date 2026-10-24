package vn.vltelecom

/** Month choices and matching for the incident and relocation report lists. */
internal object ReportMonthSelection {
    fun choices(months: Collection<String>): List<String?> =
        listOf(null) + months.distinct().sortedDescending()

    fun matches(reportMonth: String, selectedMonth: String?): Boolean =
        selectedMonth == null || reportMonth == selectedMonth

    fun label(month: String?): String = when (month) {
        null -> "Tất cả tháng"
        "" -> "Chưa rõ tháng"
        else -> "Tháng ${month.substringAfter('-')}/${month.substringBefore('-')}"
    }
}

package vn.vltelecom

import org.json.JSONArray
import org.json.JSONObject

/** Remove acknowledged incident copies locally; other reports retain the month-end rule. */
internal object LocalReportRetention {
    private val monthPattern = Regex("\\d{4}-(0[1-9]|1[0-2])")

    fun isPastMonth(reportMonth: String, currentMonth: String): Boolean =
        monthPattern.matches(reportMonth) && monthPattern.matches(currentMonth) && reportMonth < currentMonth

    fun shouldRemove(reportMonth: String, currentMonth: String, localCompleted: Boolean, serverCompleted: Boolean): Boolean =
        localCompleted && serverCompleted && isPastMonth(reportMonth, currentMonth)

    fun eligible(kind: String, reportMonth: String, currentMonth: String, localCompleted: Boolean, serverCompleted: Boolean): Boolean =
        if (kind == "Sự cố") localCompleted && serverCompleted
        else shouldRemove(reportMonth, currentMonth, localCompleted, serverCompleted)

    fun acknowledged(
        ownerConfirmed: Boolean, localCompleted: Boolean, serverCompleted: Boolean,
        pending: Boolean, refreshRequired: Boolean, canReceive: Boolean,
        localRevision: Int, serverRevision: Int, baselineRevision: Int,
        localSignature: String?, acknowledgedSignature: String?, payloadsMatch: Boolean
    ): Boolean = ownerConfirmed && localCompleted && serverCompleted && !pending && !refreshRequired && canReceive &&
        localRevision > 0 && localRevision == serverRevision && localRevision == baselineRevision &&
        !localSignature.isNullOrBlank() && localSignature == acknowledgedSignature && payloadsMatch

    private fun plain(value: Any?): Any? = when (value) {
            null, JSONObject.NULL -> null
            is JSONObject -> value.keys().asSequence().associateWith { plain(value.opt(it)) }
            is JSONArray -> (0 until value.length()).map { plain(value.opt(it)) }
            is Number -> value.toString().toBigDecimalOrNull()?.stripTrailingZeros() ?: value.toString()
            else -> value
    }
    private fun validAssets(value: Any?): Boolean = when (value) {
            is JSONObject -> value.keys().asSequence().all { key ->
                val child = value.opt(key)
                if (key in listOf("file", "originalFile") && child is String && child.isNotBlank()) Regex("[a-f0-9]{32}").matches(child)
                else validAssets(child)
            }
            is JSONArray -> (0 until value.length()).all { validAssets(value.opt(it)) }
            else -> true
    }

    fun localPayloadAcknowledged(localWire: JSONObject, baselineLocal: JSONObject): Boolean =
        validAssets(localWire) && plain(localWire) == plain(baselineLocal)

    /** GET may enrich GPS without a revision bump; accept it through the ordinary full receive path. */
    fun needsServerRefresh(localWire: JSONObject, baselineLocal: JSONObject, server: JSONObject, baselineServer: JSONObject): Boolean =
        localPayloadAcknowledged(localWire, baselineLocal) && validAssets(server) && plain(server) != plain(baselineServer)

    /** All asset references and report fields must match the accepted full upload/download. */
    fun payloadsMatch(localWire: JSONObject, baselineLocal: JSONObject, server: JSONObject, baselineServer: JSONObject): Boolean =
        localPayloadAcknowledged(localWire, baselineLocal) && validAssets(server) && plain(server) == plain(baselineServer)

    fun shouldRestore(serverOnly: Boolean, serverCompleted: Boolean): Boolean =
        serverOnly && !serverCompleted
}

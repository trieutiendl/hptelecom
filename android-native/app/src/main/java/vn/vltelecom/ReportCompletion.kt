package vn.vltelecom

import org.json.JSONObject

internal object ReportCompletion {
    fun isComplete(value: String): Boolean =
        runCatching { JSONObject(value).optBoolean("completed", false) }.getOrDefault(false)

    fun fromServer(payload: JSONObject): Boolean {
        val report = payload.optJSONObject("report")
        return if (payload.has("completed")) payload.optBoolean("completed")
        else report?.optBoolean("completed") ?: false
    }

    fun belongsInView(completed: Boolean, showCompleted: Boolean): Boolean =
        completed == showCompleted
}

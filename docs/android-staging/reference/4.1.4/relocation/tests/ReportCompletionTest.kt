package vn.vltelecom

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.json.JSONObject
import org.junit.Test

class ReportCompletionTest {
    @Test fun serverCompletionMovesReportBetweenActiveAndCompletedViews() {
        val completedOnServer = """{"completed":true}"""
        assertTrue(ReportCompletion.isComplete(completedOnServer))
        assertFalse(ReportCompletion.belongsInView(ReportCompletion.isComplete(completedOnServer), false))
        assertTrue(ReportCompletion.belongsInView(ReportCompletion.isComplete(completedOnServer), true))

        val reopenedOnServer = """{"completed":false}"""
        assertFalse(ReportCompletion.isComplete(reopenedOnServer))
        assertTrue(ReportCompletion.belongsInView(ReportCompletion.isComplete(reopenedOnServer), false))
        assertFalse(ReportCompletion.belongsInView(ReportCompletion.isComplete(reopenedOnServer), true))
    }

    @Test fun reportsWithoutCompletionRemainInActiveList() {
        assertFalse(ReportCompletion.isComplete("{}"))
        assertTrue(ReportCompletion.belongsInView(ReportCompletion.isComplete("{}"), false))
    }

    @Test fun serverCompletionFieldOverridesOlderNestedReportFlag() {
        assertTrue(ReportCompletion.fromServer(JSONObject("""{"completed":true,"report":{"completed":false}}""")))
        assertFalse(ReportCompletion.fromServer(JSONObject("""{"completed":false,"report":{"completed":true}}""")))
        assertTrue(ReportCompletion.fromServer(JSONObject("""{"report":{"completed":true}}""")))
    }
}

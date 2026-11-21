package vn.vltelecom

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object SharedReports {
    private fun prefs(app: WorkApp) = app.getSharedPreferences("server-links", Context.MODE_PRIVATE)
    fun shared(app: WorkApp, id: Long) = prefs(app).getBoolean("folder-$id-shared", false)
    fun contributor(app: WorkApp, id: Long) = shared(app,id) && ServerSession.user?.optString("role") != "admin"
    fun revoked(app: WorkApp, id: Long) = prefs(app).getBoolean("folder-$id-revoked", false)
    fun author(app: WorkApp, id: Long) = prefs(app).getString("folder-$id-author", "").orEmpty()
    suspend fun hasCollaborativeReports(app:WorkApp):Boolean {
        val saved=prefs(app)
        val ids=saved.all.keys.mapNotNull{Regex("folder-([0-9]+)-(shared|collaborators)").matchEntire(it)?.groupValues?.get(1)?.toLongOrNull()}.distinct()
        for(id in ids)if(!revoked(app,id)&&(shared(app,id)||saved.getInt("folder-$id-collaborators",0)>0)&&app.db.dao().place(id)?.kind in listOf("Sự cố","Di dời"))return true
        return false
    }
    fun ensureOwner(app: WorkApp, id: Long) { check(!contributor(app,id)) { "Báo cáo cùng làm: chỉ người lập được sửa thông tin báo cáo." } }
    fun ensureContributorActive(app: WorkApp, id: Long) { check(!revoked(app,id)) { "Bạn không còn trong danh sách cùng làm. Ảnh chưa gửi vẫn được giữ trên điện thoại." } }
    fun record(app: WorkApp, id: Long, value: JSONObject) {
        val shared = value.optBoolean("shared") && value.optString("owner") != ServerSession.user?.optString("id")
        check(prefs(app).edit().putBoolean("folder-$id-shared",shared).putBoolean("folder-$id-revoked",false)
            .putString("folder-$id-owner",value.optString("owner")).putString("folder-$id-author",value.optString("author"))
            .apply{if(value.has("collaborators"))putInt("folder-$id-collaborators",value.optInt("collaborators"))}.commit()) { "Chưa lưu được quyền báo cáo chung" }
    }
    fun markRevoked(app: WorkApp, id: Long) { prefs(app).edit().putBoolean("folder-$id-revoked",true).apply() }
    fun remote(app: WorkApp, id: Long): String = prefs(app).getString("folder-$id",null) ?: error("Hãy đồng bộ báo cáo trước")
    private suspend fun api(path: String, method: String = "GET", body: JSONObject? = null): JSONObject = try {
        ServerSession.api(path,method,body)
    } catch (e: ServerSession.Failure) {
        if (e.status in listOf(404,405)) throw ServerSession.Failure(e.status,"Server chưa hỗ trợ Người cùng làm. Hãy cập nhật server; báo cáo hiện có vẫn được giữ.")
        throw e
    }
    suspend fun members(app: WorkApp, id: Long): JSONObject {
        ensureOwner(app,id)
        val account=app.accountScope
        val result=api("mobile/folders/${remote(app,id)}/collaborators")
        check(account==app.accountScope){"Tài khoản đã thay đổi"}
        return result
    }
    suspend fun saveMembers(app: WorkApp, id: Long, revision: Int, ids: List<String>) {
        ensureOwner(app,id)
        val account=app.accountScope
        api("mobile/folders/${remote(app,id)}/collaborators","PUT",JSONObject().put("revision",revision).put("users",JSONArray(ids)))
        check(account==app.accountScope){"Tài khoản đã thay đổi"}
        // Membership may increment the server revision. The ordinary owner merge retains any new photos.
        prefs(app).edit().putBoolean("folder-$id-refresh",true).putInt("folder-$id-collaborators",ids.distinct().size).apply()
        AutoSync.refresh()
    }
}

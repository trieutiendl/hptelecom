package vn.vltelecom

import android.os.SystemClock
import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

object AutoSync {
    val status = MutableStateFlow("Tự đồng bộ khi có thay đổi")
    private var job: kotlinx.coroutines.Job? = null
    private var mxJob: kotlinx.coroutines.Job? = null
    private var mxGeneration = 0L
    private val wakeup = Channel<Unit>(Channel.CONFLATED)
    private val schedule = SyncSchedule()
    private val changes = AtomicLong()
    private val receivePending = AtomicBoolean()
    private fun signal() { wakeup.trySend(Unit) }

    // Foreground/connectivity events inspect local signatures first. Clean reports use no network.
    fun wake() {
        schedule.request(SystemClock.elapsedRealtime(), receive = receivePending.get())
        signal()
    }
    fun refresh() {
        receivePending.set(true)
        schedule.request(SystemClock.elapsedRealtime(), receive = true)
        signal()
    }
    fun syncNow() {
        receivePending.set(true)
        schedule.request(SystemClock.elapsedRealtime(), receive = true, immediate = true)
        status.value = "Đang kiểm tra đồng bộ…"
        signal()
    }
    fun changed() {
        changes.incrementAndGet()
        schedule.changed(SystemClock.elapsedRealtime())
        status.value = "Có thay đổi • tự gửi sau khi lưu xong"
        signal()
    }
    fun receiveIfPending(app: WorkApp) { if (ServerSync.hasIncoming(app)) refresh() }

    /** Called only after a successful report PUT containing MX photos without coordinates. */
    fun awaitMx(app: WorkApp, placeId: Long) {
        if (placeId <= 0 || app.accountScope == "locked" || ServerSession.token.isBlank()) return
        val state = MxRefreshSchedule.begin(System.currentTimeMillis())
        val value = JSONObject().put("attempts", state.attempts).put("dueAt", state.dueAt).toString()
        check(app.getSharedPreferences("mx-followups", Context.MODE_PRIVATE).edit().putString("place-$placeId", value).commit()) {
            "Báo cáo đã gửi nhưng chưa lưu được lịch nhận tọa độ. Mở lại ứng dụng để đồng bộ."
        }
        // Re-evaluate the earliest deadline if another report is uploaded while waiting.
        mxJob?.cancel(); mxJob = null
        resumeMx(app)
    }

    private fun resumeMx(app: WorkApp) {
        if (mxJob?.isActive == true || app.foregroundActivities == 0 || app.accountScope == "locked" || ServerSession.token.isBlank()) return
        val account = app.accountScope
        val generation = mxGeneration
        val preferences = app.getSharedPreferences("mx-followups", Context.MODE_PRIVATE)
        if (preferences.all.isEmpty()) return
        mxJob = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            fun current() = generation == mxGeneration && account == app.accountScope && ServerSession.token.isNotBlank()
            while (isActive && current() && app.foregroundActivities > 0) {
                val states = preferences.all.mapNotNull { (key, raw) ->
                    val id = key.removePrefix("place-").toLongOrNull()
                    val state = runCatching { val value = JSONObject(raw as String); MxRefreshSchedule.State(value.getInt("attempts"), value.getLong("dueAt")) }.getOrNull()
                    if (!key.startsWith("place-") || id == null || id <= 0 || state == null || !MxRefreshSchedule.valid(state)) {
                        preferences.edit().remove(key).commit(); null
                    } else Triple(key, id, state)
                }
                if (states.isEmpty()) break
                val wait = (states.minOf { it.third.dueAt } - System.currentTimeMillis()).coerceAtLeast(0)
                if (wait > 0) { delay(wait); continue }
                var receive = false
                for ((key, id, state) in states) {
                    if (!MxRefreshSchedule.due(state, System.currentTimeMillis(), app.foregroundActivities > 0, current())) continue
                    val missing = try {
                        val place = app.db.dao().place(id)
                        place != null && MxPhotoUpload.needsCoordinates(ServerSync.snapshot(app, place))
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        false
                    }
                    // A logout or background transition while reading local data must not schedule network work.
                    if (!current() || app.foregroundActivities == 0) break
                    val canReceive = app.canReceiveReport(id)
                    // A scheduled list refresh alone skips a same-revision report. Persist
                    // the per-report fetch flag before consuming this bounded attempt.
                    if (missing && !ServerSync.requestMxRefresh(app, id)) return@launch
                    val next = MxRefreshSchedule.afterCheck(state, System.currentTimeMillis(), missing, canReceive)
                    val edit = preferences.edit()
                    if (next == null) edit.remove(key)
                    else edit.putString(key, JSONObject().put("attempts", next.attempts).put("dueAt", next.dueAt).toString())
                    if (!edit.commit()) return@launch // Never repeat without durably consuming the attempt.
                    if (missing && canReceive) receive = true
                }
                if (receive && current() && app.foregroundActivities > 0) refresh()
            }
        }
    }

    suspend fun stop() {
        mxGeneration++
        job?.cancelAndJoin(); job = null
        mxJob?.cancelAndJoin(); mxJob = null
        schedule.reset(); receivePending.set(false)
        while (wakeup.tryReceive().isSuccess) { /* discard events belonging to the old account */ }
        ServerSync.drain()
    }

    fun start(app: WorkApp) {
        resumeMx(app)
        if (job?.isActive == true) return
        val account = app.accountScope
        // Shared reports also inspect revisions while this app is in the foreground.
        // The same serial loop handles the poll, so it never overlaps an upload/batch.
        refresh()
        job = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            while (isActive && account == app.accountScope && ServerSession.token.isNotEmpty()) {
                if (app.foregroundActivities == 0) { wakeup.receive(); continue }
                val wait = schedule.delayMillis(SystemClock.elapsedRealtime())
                if (wait == null) {
                    if (!SharedReports.hasCollaborativeReports(app)) { wakeup.receive(); continue }
                    val event=withTimeoutOrNull(30_000L){wakeup.receive();true}
                    if(event!=true&&account==app.accountScope&&app.foregroundActivities>0&&ServerSession.token.isNotBlank())refresh()
                    continue
                }
                if (wait > 0) { withTimeoutOrNull(wait) { wakeup.receive() }; continue }
                val batch = schedule.take(SystemClock.elapsedRealtime()) ?: continue
                runBatch(app, batch)
            }
        }
    }

    private suspend fun runBatch(app: WorkApp, batch: SyncSchedule.Batch) {
        val scanGeneration = changes.get()
        var waiting = 0
        var failed = 0
        var failureDetail = ""
        try {
            try {
                val incomplete = CameraArchive.recoverPending(app)
                if (incomplete > 0) { failed++; failureDetail = "Camera: $incomplete ảnh còn phần chưa lưu; mở Thư viện để kiểm tra." }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                failed++; failureDetail = "Thư viện Camera: ${e.message}"
            }
            try { PhotoLibrary.flushServerUploads(app) }
            catch (e: Exception) {
                if (e is CancellationException) throw e
                failed++; failureDetail = "Thư viện ảnh: ${e.message}"
            }
            try {
                val incomplete = CameraVideoArchive.recoverPending(app)
                if (incomplete > 0) { failed++; failureDetail = "Camera: $incomplete video còn phần chưa lưu; mở Thư viện để kiểm tra." }
                CameraVideoArchive.flushServerUploads(app)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                failed++; failureDetail = "Thư viện video: ${e.message}"
            }
            app.flushReport()
            if (batch.receive) {
                ServerSession.refresh()
                try { ServerSync.receiveOwnFolders(app) { status.value = it } }
                catch (e: Exception) {
                    if (e is CancellationException || e is ServerSession.Failure && e.status == 401) throw e
                    failed++; failureDetail = "Chưa nhận đủ báo cáo: ${e.message}"
                }
                try { ServerSync.receiveCableTasks(app) { status.value = it } }
                catch (e: Exception) {
                    if (e is CancellationException || e is ServerSession.Failure && e.status == 401) throw e
                    failed++; failureDetail = "Nhận tuyến: ${e.message}"
                }
                receivePending.set(failed > 0)
            }
            for (kind in ServerSession.moduleNames.values) {
                if (!ServerSession.allowed(kind)) continue
                for (place in app.db.dao().places(kind)) {
                    currentCoroutineContext().ensureActive()
                    try {
                        if (!ServerSync.syncState(app, place).second) continue
                        waiting++
                        try { ServerSync.upload(app, place) { status.value = it } }
                        catch (e: ServerSession.Failure) {
                            // upload has cleared stale asset references; retry this repair once only.
                            if (e.status != 400 || !e.message.orEmpty().contains("Thiếu ảnh")) throw e
                            ServerSync.upload(app, place) { status.value = it }
                        }
                        if (app.db.dao().place(place.id) == null) { waiting--; continue }
                        if (!ServerSync.syncState(app, place).second) waiting--
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        if (e is ServerSession.Failure && e.status in listOf(400, 403, 409, 413)) {
                            failed++; failureDetail = "${place.name}: ${e.message}"
                        } else throw e
                    }
                }
            }
            var completedReportsRemoved=0
            if(failed==0&&waiting==0&&changes.get()==scanGeneration&&!ServerSync.hasIncoming(app)){
                completedReportsRemoved=ServerSync.removePastMonthReports(app)
            }
            status.value = when {
                failed > 0 -> "$failed mục cần kiểm tra. $failureDetail"
                changes.get() != scanGeneration -> "Có thay đổi mới đang chờ gửi lên server"
                waiting > 0 -> "$waiting thư mục còn thay đổi • bấm Đồng bộ để thử lại"
                ServerSync.hasIncoming(app) -> "Có bản mới từ server • quay về danh sách để nhận cập nhật"
                completedReportsRemoved > 0 -> "Đã lưu $completedReportsRemoved báo cáo hoàn thành trên server và gỡ bản trên điện thoại"
                else -> "Đã đồng bộ • tự gửi khi có thay đổi mới"
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            status.value = if (e is ServerSession.Failure && e.status == 401)
                "Phiên đăng nhập hết hạn hoặc tài khoản bị khóa. Đăng nhập lại để đồng bộ; dữ liệu vẫn giữ trên máy."
            else "Chưa kết nối được server • dữ liệu giữ trên máy. Tự thử khi có mạng lại, mở lại ứng dụng hoặc bấm Đồng bộ."
            // No timed network retries: the next edit, reconnect, foreground or manual event retries.
        }
    }
}

package vn.vltelecom

/** An idle queue has no deadline: elapsed time alone never starts another sync. */
internal class SyncSchedule {
    data class Batch(val receive: Boolean)
    private var dueAt: Long? = null
    private var receive = false

    @Synchronized fun changed(now: Long) { dueAt = now + 10_000; receive = true }

    @Synchronized fun request(now: Long, receive: Boolean = false, immediate: Boolean = false) {
        this.receive = this.receive || receive
        if (immediate) dueAt = now else if (dueAt == null) dueAt = now + 1_000
    }

    @Synchronized fun take(now: Long): Batch? {
        if (dueAt?.let { now >= it } != true) return null
        return Batch(receive).also { reset() }
    }

    @Synchronized fun delayMillis(now: Long): Long? = dueAt?.let { (it - now).coerceAtLeast(0) }
    @Synchronized fun reset() { dueAt = null; receive = false }
}

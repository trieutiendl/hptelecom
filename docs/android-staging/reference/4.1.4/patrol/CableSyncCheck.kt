package vn.vltelecom

internal enum class CableSyncCheck { MATCH, RESEND, CONFLICT }

// A cached upload receipt is insufficient when the current server disagrees.
// Never replace a newer remote revision automatically.
internal fun reconcileCableSync(localRevision:Int,remoteRevision:Int,localPoints:Int,remotePoints:Int,localCompleted:Boolean,remoteCompleted:Boolean):CableSyncCheck = when {
 localRevision!=remoteRevision -> CableSyncCheck.CONFLICT
 localPoints!=remotePoints || localCompleted!=remoteCompleted -> CableSyncCheck.RESEND
 else -> CableSyncCheck.MATCH
}

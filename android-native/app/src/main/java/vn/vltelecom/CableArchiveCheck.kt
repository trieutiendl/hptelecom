package vn.vltelecom

/** A completed assigned route is removable only after its exact local snapshot is acknowledged. */
object CableArchiveCheck {
    fun allowed(
        localCompleted: Boolean,
        serverCompleted: Boolean,
        pending: Boolean,
        localRevision: Int,
        serverRevision: Int,
        localSignature: String?,
        acknowledgedSignature: String?,
        refreshRequired: Boolean
    ): Boolean = localCompleted && serverCompleted && !pending && !refreshRequired &&
        localRevision == serverRevision && !localSignature.isNullOrBlank() &&
        localSignature == acknowledgedSignature
}

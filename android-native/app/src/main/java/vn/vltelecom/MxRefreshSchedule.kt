package vn.vltelecom

/** Four bounded follow-ups for one acknowledged MX upload, never an idle poll. */
internal object MxRefreshSchedule {
    private val delays = longArrayOf(15_000, 30_000, 60_000, 120_000)
    data class State(val attempts: Int, val dueAt: Long)

    fun begin(now: Long) = State(0, now + delays[0])
    fun valid(state: State) = state.attempts in delays.indices && state.dueAt > 0
    fun due(state: State, now: Long, foreground: Boolean, sameAccount: Boolean) =
        valid(state) && foreground && sameAccount && now >= state.dueAt

    /** Do not spend the receive budget while the editor deliberately blocks replacement. */
    fun afterCheck(state: State, now: Long, missing: Boolean, canReceive: Boolean): State? =
        when {
            !missing || !valid(state) -> null
            !canReceive -> State(state.attempts, now + delays[0])
            else -> consume(state, now)
        }

    /** Persist consumption before requesting a receive, so restarts cannot reset the budget. */
    fun consume(state: State, now: Long): State? {
        if (!valid(state)) return null
        val attempts = state.attempts + 1
        return if (attempts < delays.size) State(attempts, now + delays[attempts]) else null
    }
}

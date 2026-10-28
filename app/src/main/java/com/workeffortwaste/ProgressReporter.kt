package com.workeffortwaste

/** Worker-owned throttling shared by backup and restore; the clock returns monotonic milliseconds. */
internal data class ProgressPhase(
    val name: String,
    val totalBytes: Long,
    val status: StatusMessage,
    val weight: Double = totalBytes.toDouble(),
)

internal class ProgressReporter(
    private val clock: () -> Long,
    private val phases: List<ProgressPhase>,
    private val maximumFraction: Float = 1f,
    private val publish: (status: StatusMessage, fraction: Float) -> Unit,
) {
    private var lastPhase: String? = null
    private var lastPublishTime = 0L
    private val totalWork = phases.sumOf { it.weight }

    init {
        require(phases.isNotEmpty() && phases.all { it.totalBytes > 0 })
        require(phases.all { it.weight.isFinite() && it.weight > 0 })
        require(maximumFraction > 0f && maximumFraction <= 1f)
        require(phases.map { it.name }.distinct().size == phases.size)
    }

    fun report(phase: String, bytes: Long, total: Long) {
        val now = clock()
        if (phase != lastPhase || bytes == total || now - lastPublishTime >= 100) {
            lastPhase = phase
            lastPublishTime = now
            val phaseIndex = phases.indexOfFirst { it.name == phase }
            require(phaseIndex >= 0) { "Unplanned progress phase: $phase" }
            val plannedPhase = phases[phaseIndex]
            require(total == plannedPhase.totalBytes) { "Unexpected total for progress phase: $phase" }
            val completedBefore = phases.take(phaseIndex).sumOf { it.weight }
            val completed = completedBefore +
                    bytes.coerceIn(0, plannedPhase.totalBytes).toDouble() / plannedPhase.totalBytes * plannedPhase.weight
            publish(plannedPhase.status, (completed / totalWork).toFloat().coerceAtMost(maximumFraction))
        }
    }
}

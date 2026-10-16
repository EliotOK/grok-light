package com.eliotok.groklight

internal enum class MirrorConnectionStage { CONNECTING, CONNECTED, RECONNECTING, FAILED }

/** Monotonic deadlines also cover a WebView that stops answering health probes. */
internal class MirrorConnectionMonitor(private val startedAt: Long) {
    private var connectedBefore = false
    private var unhealthySince: Long? = startedAt
    private var sampledAt: Long? = null
    private var healthy = false

    fun record(health: MirrorPageHealth, now: Long) {
        sampledAt = now
        healthy = health.ready && health.connection == "open"
        if (healthy) {
            connectedBefore = true
            unhealthySince = null
        } else if (unhealthySince == null) {
            unhealthySince = now
        }
    }

    fun stage(now: Long): MirrorConnectionStage {
        val lastSample = sampledAt
        if (healthy && lastSample != null && now - lastSample < 4_500L) {
            return MirrorConnectionStage.CONNECTED
        }
        val waitingSince = unhealthySince ?: (lastSample?.plus(4_500L) ?: startedAt)
        val expired = if (connectedBefore) now - waitingSince >= 10_000L
            else now - startedAt >= 30_000L
        return when {
            expired -> MirrorConnectionStage.FAILED
            connectedBefore -> MirrorConnectionStage.RECONNECTING
            else -> MirrorConnectionStage.CONNECTING
        }
    }
}

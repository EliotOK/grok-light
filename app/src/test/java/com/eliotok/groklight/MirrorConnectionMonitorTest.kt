package com.eliotok.groklight

import org.junit.Assert.assertEquals
import org.junit.Test

class MirrorConnectionMonitorTest {
    private val online = MirrorPageHealth("open", true)
    private val offline = MirrorPageHealth("closed", true)

    @Test fun initialConnectionHasABoundedDeadlineEvenWithoutJavaScriptReplies() {
        val monitor = MirrorConnectionMonitor(100L)
        assertEquals(MirrorConnectionStage.CONNECTING, monitor.stage(30_099L))
        assertEquals(MirrorConnectionStage.FAILED, monitor.stage(30_100L))
    }

    @Test fun prolongedDisconnectReturnsToPairingAfterReconnectGrace() {
        val monitor = MirrorConnectionMonitor(0L)
        monitor.record(online, 1_000L)
        assertEquals(MirrorConnectionStage.CONNECTED, monitor.stage(1_000L))
        monitor.record(offline, 2_000L)
        assertEquals(MirrorConnectionStage.RECONNECTING, monitor.stage(2_000L))
        monitor.record(MirrorPageHealth("connecting", false), 8_000L)
        assertEquals(MirrorConnectionStage.FAILED, monitor.stage(12_000L))
    }

    @Test fun recoveredConnectionStartsANewGracePeriodOnNextDisconnect() {
        val monitor = MirrorConnectionMonitor(0L)
        monitor.record(online, 1_000L)
        monitor.record(offline, 2_000L)
        monitor.record(online, 9_000L)
        assertEquals(MirrorConnectionStage.CONNECTED, monitor.stage(9_000L))
        monitor.record(offline, 10_000L)
        assertEquals(MirrorConnectionStage.RECONNECTING, monitor.stage(19_999L))
        assertEquals(MirrorConnectionStage.FAILED, monitor.stage(20_000L))
    }

    @Test fun unresponsivePageAfterConnectionAlsoReturnsToPairing() {
        val monitor = MirrorConnectionMonitor(0L)
        monitor.record(online, 1_000L)
        assertEquals(MirrorConnectionStage.RECONNECTING, monitor.stage(5_500L))
        assertEquals(MirrorConnectionStage.FAILED, monitor.stage(15_500L))
    }

    @Test fun loadingAnEmptyRootDoesNotCountAsConnected() {
        val monitor = MirrorConnectionMonitor(0L)
        monitor.record(MirrorPageHealth("open", false), 29_000L)
        assertEquals(MirrorConnectionStage.FAILED, monitor.stage(30_000L))
    }
}

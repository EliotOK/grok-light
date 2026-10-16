package com.eliotok.groklight

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MirrorConnectionTest {
    @Test fun preservesExplicitPortAndPairing() {
        assertEquals("http://192.168.1.8:58515/t/paired-token/",
            Prefs.normalizeMirrorUrl("http://192.168.1.8:58515/t/paired-token/", ""))
    }

    @Test fun correctsVirtualAdapterWithoutChangingPairing() {
        assertEquals("http://192.168.1.8:63867/t/paired-token/",
            Prefs.normalizeMirrorUrl("http://198.18.0.1:63867/t/paired-token/", "192.168.1.8"))
        assertNull(Prefs.normalizeMirrorUrl("http://127.0.0.1:63867/t/token/", ""))
    }

    @Test fun respectsStandardHttpAndHttpsPorts() {
        assertEquals("http://mirror.example/t/token/",
            Prefs.normalizeMirrorUrl("http://mirror.example/t/token/", ""))
        assertEquals("https://mirror.example/t/token/",
            Prefs.normalizeMirrorUrl("https://mirror.example/t/token/", ""))
    }

    @Test fun requiresReadyPageAndOpenTransportForConnectedState() {
        val open = MirrorPageHealth.parse(JSONObject.quote("""{"connection":"open","ready":true}"""))
        assertEquals("open", open.connection)
        assertTrue(open.ready)
        val disconnected = MirrorPageHealth.parse(JSONObject.quote("""{"connection":"closed","ready":true}"""))
        assertEquals("closed", disconnected.connection)
        assertEquals("unknown", MirrorPageHealth.parse("null").connection)
        assertFalse(MirrorPageHealth.parse("null").ready)
    }
}

package com.eliotok.groklight

import org.json.JSONObject
import org.json.JSONTokener

/** Page readiness and mirror transport state, independent of navigator.onLine. */
internal data class MirrorPageHealth(val connection: String, val ready: Boolean) {
    companion object {
        fun parse(result: String?): MirrorPageHealth {
            return try {
                val text = JSONTokener(result ?: "null").nextValue() as? String
                val value = JSONObject(text ?: "{}")
                MirrorPageHealth(value.optString("connection", "unknown"), value.optBoolean("ready"))
            } catch (_: Exception) {
                MirrorPageHealth("unknown", false)
            }
        }
    }
}

internal const val PAGE_HEALTH_JS = """
(function(){
  var root = document.getElementById('root');
  var health = window.__GROK_MIRROR_HEALTH__;
  var probe = window.__glProbe;
  var ready = !!root && root.childElementCount > 0 &&
    !root.querySelector('[data-testid="setup-booting"]') &&
    (root.innerText || '').trim().length > 0;
  return JSON.stringify({
    connection: health ? health.connection : (probe ? probe.state : 'unknown'),
    ready: ready
  });
})()
"""

package com.eliotok.groklight

import android.content.Context
import java.net.URI

/**
 * 本地小存储：镜像链接与电脑的局域网 IP 只存在手机本地，不上传任何服务器。
 *
 * 关于「主机自动纠正」：远程控制面板有时会把代理软件虚拟网卡的地址
 * （Clash / Mihomo 的 TUN 常见 198.18.x.x，或回环 127.0.0.1）当成局域网地址写进链接，
 * 手机侧没有任何路由能到这些地址，浏览器表现就是一直转圈加载。这里统一换成用户填写的电脑局域网 IP。
 */
object Prefs {
    private const val FILE = "grok_mirror"
    private const val KEY_URL = "mirror_url"
    private const val KEY_LAN_HOST = "lan_host"
    private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
    private const val KEY_LAST_UPDATE_CHECK = "last_update_check"

    /** 面板链接没带端口时的兜底端口（Grok App 镜像主机的默认端口）。 */
    private const val DEFAULT_MIRROR_PORT = 63867

    private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "0.0.0.0", "::1")

    /** 这些地址在手机侧不可达：回环，以及 Clash / Mihomo 的 fake-ip 段（198.18.0.0/15）。 */
    fun isBogusHost(host: String?): Boolean {
        val h = host?.trim()?.lowercase() ?: return true
        if (h in LOOPBACK_HOSTS) return true
        return h.startsWith("198.18.") || h.startsWith("198.19.")
    }

    fun lanHost(ctx: Context): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_LAN_HOST, "").orEmpty()

    fun setLanHost(ctx: Context, value: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY_LAN_HOST, value.trim()).apply()
    }

    fun remoteUrl(ctx: Context): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_URL, "").orEmpty()

    fun setRemoteUrl(ctx: Context, url: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY_URL, url.trim()).apply()
    }

    fun keepScreenOn(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_KEEP_SCREEN_ON, false)

    fun setKeepScreenOn(ctx: Context, value: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_KEEP_SCREEN_ON, value).apply()
    }

    fun lastUpdateCheck(ctx: Context): Long =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_UPDATE_CHECK, 0L)

    fun setLastUpdateCheck(ctx: Context, millis: Long) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putLong(KEY_LAST_UPDATE_CHECK, millis).apply()
    }

    /** 链接里的主机是否属于「手机不可达」那一类；是则返回它，供界面提示。 */
    fun bogusHostOf(input: String): String? {
        val host = parse(input)?.host ?: return null
        return if (isBogusHost(host)) host else null
    }

    /**
     * 认远程控制面板给出的镜像链接：http(s)://<主机>:<端口>/t/<token>/
     * 主机不可达且用户又没填局域网 IP 时返回 null，由界面提示去填。
     */
    fun normalizeMirrorUrl(input: String, lanHost: String): String? {
        val uri = parse(input) ?: return null
        val host = uri.host ?: return null
        val path = uri.path ?: return null
        if (!path.startsWith("/t/")) return null

        val bogus = isBogusHost(host)
        val replacement = lanHost.trim()
        if (bogus && replacement.isEmpty()) return null

        val scheme = if (bogus) "http" else if (uri.scheme.equals("https", true)) "https" else "http"
        val port = when {
            uri.port > 0 -> uri.port
            scheme == "https" -> 443
            else -> DEFAULT_MIRROR_PORT
        }
        val hostPart = if (bogus) replacement else host
        val portPart =
            if ((scheme == "http" && port == 80) || (scheme == "https" && port == 443)) "" else ":$port"
        val query = uri.rawQuery?.let { "?$it" } ?: ""
        return "$scheme://$hostPart$portPart$path$query"
    }

    private fun parse(input: String): URI? {
        var s = input.trim().trim('"', '\'', '<', '>')
        if (s.isEmpty()) return null
        if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "http://$s"
        return try {
            URI(s)
        } catch (_: Exception) {
            null
        }
    }
}
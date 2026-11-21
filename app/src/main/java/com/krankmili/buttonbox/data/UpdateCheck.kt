package com.krankmili.buttonbox.data

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(val version: String, val url: String, val notes: String)

/**
 * Looks at version.json in the project's GitHub repo and reports a newer release, if any.
 * Only notifies; installing is always done by the user (Android asks for it anyway).
 */
object UpdateCheck {
    const val CURRENT = "1.9.3"
    private const val SOURCE = "https://raw.githubusercontent.com/krankmilitv/krankmili-button-box/main/version.json"

    /** Returns the newer release, or null when up to date / offline / anything fails. */
    fun fetch(): UpdateInfo? = try {
        val c = URL(SOURCE).openConnection() as HttpURLConnection
        c.connectTimeout = 5000
        c.readTimeout = 5000
        c.requestMethod = "GET"
        try {
            if (c.responseCode != 200) null
            else {
                val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                val v = j.getString("version")
                if (isNewer(v, CURRENT)) UpdateInfo(v, j.optString("url"), j.optString("notes")) else null
            }
        } finally {
            c.disconnect()
        }
    } catch (_: Exception) {
        null
    }

    fun isNewer(a: String, b: String): Boolean {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }
}

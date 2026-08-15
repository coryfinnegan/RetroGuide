package com.ersatz.retroguide

import android.content.Context
import android.content.SharedPreferences

/**
 * Where the server address lives between launches.
 *
 * Nothing about a particular network is baked into the app: on a fresh install
 * there is no host at all, and the setup screen either finds one or asks. That
 * also means a changed server IP is a ten-second fix on the sofa rather than a
 * rebuild and re-sideload.
 */
object Prefs {
    private const val FILE = "retroguide"
    private const val KEY_HOST = "host"
    private const val KEY_LAST_CHANNEL = "last_channel"

    private fun sp(c: Context): SharedPreferences =
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** "192.168.1.50:8409", or null when the app has never been set up. */
    fun host(c: Context): String? = sp(c).getString(KEY_HOST, null)

    fun setHost(c: Context, host: String) {
        sp(c).edit().putString(KEY_HOST, host.trim()).apply()
        Source.host = host.trim()
    }

    fun clearHost(c: Context) = sp(c).edit().remove(KEY_HOST).apply()

    /** Resume on whatever was last watched, the way a real set-top box does. */
    fun lastChannel(c: Context): String? = sp(c).getString(KEY_LAST_CHANNEL, null)

    fun setLastChannel(c: Context, number: String) {
        sp(c).edit().putString(KEY_LAST_CHANNEL, number).apply()
    }

    /** Load any saved host into Source. Returns false when setup is still needed. */
    fun apply(c: Context): Boolean {
        val h = host(c) ?: return false
        Source.host = h
        return true
    }
}

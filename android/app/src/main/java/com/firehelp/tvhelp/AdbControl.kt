package com.firehelp.tvhelp

import android.content.Context
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File
import java.util.concurrent.Executors

/**
 * Presses remote buttons by talking to the device's own ADB service on 127.0.0.1:5555.
 * Needs "ADB debugging" switched on once in Developer options, and the person at the TV
 * to accept the "Allow USB debugging?" prompt (tick "Always allow") the first time.
 */
class AdbControl(context: Context) {
    private val priv = File(context.filesDir, "adbkey")
    private val pub = File(context.filesDir, "adbkey.pub")
    private val exec = Executors.newSingleThreadExecutor()
    @Volatile private var dadb: Dadb? = null

    private fun keyPair(): AdbKeyPair {
        if (!priv.exists() || !pub.exists()) AdbKeyPair.generate(priv, pub)
        return AdbKeyPair.read(priv, pub)
    }

    /** Blocking. Returns null when working, otherwise a plain-English problem. */
    fun connect(): String? {
        return try {
            try { dadb?.close() } catch (_: Exception) {}
            dadb = null
            val d = Dadb.create("127.0.0.1", 5555, keyPair())
            val out = d.shell("echo ok").output.trim()
            if (out == "ok") { dadb = d; null } else "ADB answered unexpectedly."
        } catch (e: Exception) {
            val msg = e.message ?: e.toString()
            when {
                msg.contains("refused", true) || msg.contains("ECONNREFUSED") ->
                    "ADB debugging is off. Turn it on in Settings > My device > Developer options."
                else -> "Couldn't connect to ADB ($msg). Accept the \"Allow USB debugging\" prompt if it appears."
            }
        }
    }

    fun connectAsync(done: (String?) -> Unit) = exec.execute { done(connect()) }

    /** Queue a shell command; reconnects once if the link dropped. */
    fun shell(cmd: String) = exec.execute {
        try {
            val d = dadb ?: run { connect(); dadb } ?: return@execute
            d.shell(cmd)
        } catch (_: Exception) {
            dadb = null
            try { connect(); dadb?.shell(cmd) } catch (_: Exception) {}
        }
    }

    fun key(name: String) {
        val code = KEYS[name] ?: return
        shell("input keyevent $code")
    }

    fun text(t: String) {
        val clean = t.take(200).replace("'", "").replace(" ", "%s")
        if (clean.isNotEmpty()) shell("input text '$clean'")
    }

    /** Push an APK to the TV and (re)install it. Runs on the ADB thread; result is plain English. */
    fun install(apk: File, onResult: (Boolean, String) -> Unit) = exec.execute {
        val remote = "/data/local/tmp/tvhelp_update.apk"
        try {
            val d = dadb ?: run { connect(); dadb }
            if (d == null) { onResult(false, "Couldn't reach ADB on the TV. Check ADB debugging is on."); return@execute }
            d.push(apk, remote)
            val out = try { d.shell("pm install -r $remote").output.trim() } finally {
                try { d.shell("rm -f $remote") } catch (_: Exception) {}
            }
            if (out.contains("Success", true)) onResult(true, "Installed OK")
            else onResult(false, if (out.isBlank()) "Install failed (no reason given)" else out.take(160))
        } catch (e: Exception) {
            onResult(false, e.message ?: e.toString())
        }
    }

    fun close() { try { dadb?.close() } catch (_: Exception) {}; exec.shutdownNow() }

    companion object {
        val KEYS = mapOf(
            "UP" to 19, "DOWN" to 20, "LEFT" to 21, "RIGHT" to 22, "OK" to 23,
            "BACK" to 4, "HOME" to 3, "MENU" to 82, "SETTINGS" to 176, "SEARCH" to 84,
            "REWIND" to 89, "PLAYPAUSE" to 85, "FORWARD" to 90,
            "VOLUP" to 24, "VOLDOWN" to 25, "MUTE" to 164, "SLEEP" to 223
        )
    }
}

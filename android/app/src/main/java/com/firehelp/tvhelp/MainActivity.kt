package com.firehelp.tvhelp

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * The only screen the family member sees. It shows the 6-digit code to read out, and when a
 * helper asks to connect it shows big Allow / No thanks buttons. Nothing is shared until they
 * press Allow and then accept Android's own "Start now" screen-record prompt.
 */
class MainActivity : Activity() {

    private lateinit var title: TextView
    private lateinit var codeView: TextView
    private lateinit var status: TextView
    private lateinit var allow: Button
    private lateinit var deny: Button
    private lateinit var hint: TextView

    private val listener = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        title = findViewById(R.id.title)
        codeView = findViewById(R.id.code)
        status = findViewById(R.id.status)
        allow = findViewById(R.id.allow)
        deny = findViewById(R.id.deny)
        hint = findViewById(R.id.hint)

        allow.setOnClickListener { onAllow() }
        deny.setOnClickListener { service?.respond(false) }

        ensureOverlayPermission()
        startService(Intent(this, HelpService::class.java))
        bindToService()
    }

    override fun onResume() { super.onResume(); AppState.addListener(listener); render() }
    override fun onPause() { super.onPause(); AppState.removeListener(listener) }

    private fun render() {
        val s = AppState.phase
        codeView.visibility = if (AppState.code.isNotEmpty() && s != AppState.Phase.IN_SESSION) View.VISIBLE else View.GONE
        codeView.text = AppState.code.chunked(3).joinToString(" ")
        val requesting = s == AppState.Phase.REQUEST
        allow.visibility = if (requesting) View.VISIBLE else View.GONE
        deny.visibility = if (requesting) View.VISIBLE else View.GONE
        if (requesting) allow.requestFocus()

        title.text = when (s) {
            AppState.Phase.CONNECTING -> "Starting up…"
            AppState.Phase.READY -> "Read this code to the person helping you"
            AppState.Phase.REQUEST -> "Allow them to see your TV and help?"
            AppState.Phase.IN_SESSION -> "Connected — they can see your TV now"
        }
        status.text = AppState.message
        hint.text = if (!AppState.adbReady && AppState.adbMessage.isNotEmpty() && s != AppState.Phase.CONNECTING)
            "Button control setup: " + AppState.adbMessage else ""
    }

    private fun onAllow() {
        service?.respond(true)
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECT)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PROJECT) {
            if (resultCode == RESULT_OK && data != null) {
                val i = Intent(this, HelpService::class.java).apply {
                    action = HelpService.ACTION_START_SHARE
                    putExtra(HelpService.EXTRA_RESULT, resultCode)
                    putExtra(HelpService.EXTRA_DATA, data)
                }
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
            } else {
                service?.respond(false)
                Toast.makeText(this, "Not shared. Nothing happened.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun ensureOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                Toast.makeText(this, "Please allow the on-screen banner, then press Back.", Toast.LENGTH_LONG).show()
            } catch (_: Exception) {}
        }
    }

    // --- bind so the Allow/Deny buttons can reach the running service ---
    private var service: HelpService? = null
    private val conn = object : android.content.ServiceConnection {
        override fun onServiceConnected(n: android.content.ComponentName?, b: android.os.IBinder?) {}
        override fun onServiceDisconnected(n: android.content.ComponentName?) { service = null }
    }
    private fun bindToService() {
        // Simple approach: keep a static reference set by the service.
        service = Holder.instance
        AppState.addListener { service = Holder.instance }
    }

    companion object { const val REQ_PROJECT = 1001 }
}

/** Lets the activity call respond() on the live service without a bound-service dance. */
object Holder { @Volatile var instance: HelpService? = null }

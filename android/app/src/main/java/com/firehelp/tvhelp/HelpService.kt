package com.firehelp.tvhelp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Holds the link to the relay while the app is open. The screen is only shared after the
 * person at the TV approves (and Android's own "Start now" prompt is accepted), buttons are
 * pressed through AdbControl, and a red banner stays on the TV for the whole session so the
 * household always knows a helper is connected. Approval and stopping stay on the TV side.
 */
class HelpService : Service() {

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private var ws: WebSocket? = null
    private val main = Handler(Looper.getMainLooper())
    private lateinit var adb: AdbControl
    private var stopped = false

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var lastFrame = 0L
    private var frameGapMs = 160L
    private var jpegQuality = 50
    private var overlay: TextView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Holder.instance = this
        adb = AdbControl(this)
        startForeground(NOTE_ID, notification("Help Tool is open"))
        adb.connectAsync { problem ->
            AppState.update { adbReady = problem == null; adbMessage = problem ?: "" }
        }
        connect()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A granted screen-share permission is delivered here from MainActivity.
        if (intent?.action == ACTION_START_SHARE) {
            val code = intent.getIntExtra(EXTRA_RESULT, 0)
            val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
            if (data != null) startSharing(code, data)
        } else if (intent?.action == ACTION_STOP) {
            send(JSONObject().put("type", "stop"))
            endSession("Stopped on the TV.")
        }
        return START_STICKY
    }

    // ---------- relay link ----------
    private fun connect() {
        if (stopped) return
        AppState.update { phase = AppState.Phase.CONNECTING; message = "Connecting…" }
        val req = Request.Builder().url(BuildConfig.RELAY_URL).build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val hello = JSONObject()
                    .put("type", "hello")
                    .put("name", deviceName())
                    .put("model", Build.MODEL)
                    .put("version", "1.0")
                webSocket.send(hello.toString())
            }
            override fun onMessage(webSocket: WebSocket, text: String) = handle(text)
            override fun onFailure(webSocket: WebSocket, t: Throwable, r: Response?) {
                AppState.update { phase = AppState.Phase.CONNECTING; message = "Lost connection. Retrying…" }
                if (!stopped) main.postDelayed({ connect() }, 4000)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!stopped) main.postDelayed({ connect() }, 4000)
            }
        })
    }

    private fun handle(text: String) {
        val m = try { JSONObject(text) } catch (e: Exception) { return }
        when (m.optString("type")) {
            "code" -> AppState.update { code = m.optString("code"); phase = AppState.Phase.READY; message = "Ready" }
            "request" -> AppState.update { phase = AppState.Phase.REQUEST; message = "A helper would like to connect" }
            "key" -> if (AppState.phase == AppState.Phase.IN_SESSION) adb.key(m.optString("key"))
            "text" -> if (AppState.phase == AppState.Phase.IN_SESSION) adb.text(m.optString("text"))
            "quality" -> applyQuality(m.optString("quality"))
            "install" -> doInstall(m.optString("url"), m.optString("name", "update.apk"))
            "apps-list" -> sendAppList()
            "app-action" -> doAppAction(m.optString("action"), m.optString("pkg"))
            "app-batch" -> doAppBatch(m.optString("action"), m.optJSONArray("pkgs"))
            "ended" -> endSession(m.optString("reason", "Session ended."))
        }
    }

    // ---------- app management (list + uninstall/disable/restore over ADB) ----------

    /** Packages the app will never act on, however the controller asks. */
    private fun isProtected(pkg: String): Boolean {
        if (pkg.isBlank() || pkg == packageName) return true
        val p = pkg.lowercase()
        if (p == "android" || p.startsWith("android.")) return true
        val critical = setOf(
            "com.android.systemui", "com.android.shell", "com.android.settings",
            "com.android.phone", "com.android.providers.settings", "com.android.providers.media",
            "com.android.keychain", "com.android.certinstaller", "com.android.inputdevices",
            "com.amazon.device.sync", "com.amazon.tv.settings"
        )
        if (pkg in critical) return true
        if (p.contains("launcher") || p.contains("systemui") || p.contains("inputmethod")) return true
        return false
    }

    private fun sendAppList() = Thread {
        try {
            val pm = packageManager
            val flags = PackageManager.MATCH_UNINSTALLED_PACKAGES
            val pkgs = pm.getInstalledPackages(flags)
            val arr = JSONArray()
            for (pi in pkgs) {
                val ai = pi.applicationInfo ?: continue
                val isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                    (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                val installed = (ai.flags and ApplicationInfo.FLAG_INSTALLED) != 0
                val label = try { pm.getApplicationLabel(ai).toString() } catch (_: Exception) { pi.packageName }
                arr.put(
                    JSONObject()
                        .put("pkg", pi.packageName)
                        .put("label", label)
                        .put("system", isSystem)
                        .put("enabled", ai.enabled)
                        .put("installed", installed)
                        .put("protectedPkg", isProtected(pi.packageName))
                )
            }
            send(JSONObject().put("type", "apps").put("list", arr))
        } catch (e: Exception) {
            send(JSONObject().put("type", "apps").put("error", e.message ?: "Couldn't read the app list"))
        }
    }.start()

    private fun doAppAction(action: String, pkg: String) {
        if (pkg.isBlank()) return
        if (isProtected(pkg)) {
            toastMsg("Refused: $pkg is protected")
            send(appResult(pkg, action, false, "That package is protected and can't be changed."))
            return
        }
        val cmd = when (action) {
            "uninstall"     -> "pm uninstall $pkg"
            "remove-system" -> "pm uninstall -k --user 0 $pkg"
            "disable"       -> "pm disable-user --user 0 $pkg"
            "enable"        -> "pm enable $pkg"
            "restore"       -> "cmd package install-existing $pkg"
            else -> return
        }
        val okMark = when (action) {
            "disable" -> "disabled"; "enable" -> "enabled"
            "restore" -> "installed for user"; else -> "Success"
        }
        toastMsg("$action: $pkg")
        adb.shellOut(cmd) { out ->
            val ok = out.contains(okMark, ignoreCase = true)
            send(appResult(pkg, action, ok, if (out.isBlank()) "(no output)" else out.take(180)))
        }
    }

    /** Apply one action to a list of packages in sequence (presets). Reports a summary. */
    private fun doAppBatch(action: String, pkgs: JSONArray?) {
        if (pkgs == null || pkgs.length() == 0) return
        val template = when (action) {
            "disable"       -> "pm disable-user --user 0 %s"
            "enable"        -> "pm enable %s"
            "remove-system" -> "pm uninstall -k --user 0 %s"
            "restore"       -> "cmd package install-existing %s"
            else -> return
        }
        val okMark = when (action) {
            "disable" -> "disabled"; "enable" -> "enabled"
            "restore" -> "installed for user"; else -> "Success"
        }
        toastMsg("Applying preset ($action) to ${pkgs.length()} apps…")
        Thread {
            val results = JSONArray()
            var done = 0
            for (i in 0 until pkgs.length()) {
                val pkg = pkgs.optString(i)
                if (pkg.isBlank()) continue
                if (isProtected(pkg)) { results.put(JSONObject().put("pkg", pkg).put("ok", false).put("message", "protected")); continue }
                val out = adb.shellOutSync(template.format(pkg))
                val ok = out.contains(okMark, ignoreCase = true)
                if (ok) done++
                results.put(JSONObject().put("pkg", pkg).put("ok", ok).put("message", if (out.isBlank()) "(not present)" else out.take(120)))
            }
            toastMsg("Preset done: $done changed")
            send(JSONObject().put("type", "app-batch-result").put("action", action).put("done", done).put("results", results))
        }.start()
    }

    private fun appResult(pkg: String, action: String, ok: Boolean, msg: String) =
        JSONObject().put("type", "app-action-result").put("pkg", pkg).put("action", action).put("ok", ok).put("message", msg)

    private fun toastMsg(t: String) = main.post {
        android.widget.Toast.makeText(this, t, android.widget.Toast.LENGTH_LONG).show()
    }

    /** Download a pushed APK and (re)install it over ADB. Visible to the household via a toast. */
    private fun doInstall(url: String, name: String) {
        if (url.isBlank()) return
        main.post { android.widget.Toast.makeText(this, "Installing update: $name", android.widget.Toast.LENGTH_LONG).show() }
        AppState.update { message = "Installing update: $name…" }
        Thread {
            try {
                val resp = client.newCall(Request.Builder().url(url).build()).execute()
                if (!resp.isSuccessful) { resp.close(); reportInstall(false, "Download failed (${resp.code})"); return@Thread }
                val apk = java.io.File(cacheDir, "update.apk")
                resp.body?.byteStream()?.use { input -> apk.outputStream().use { input.copyTo(it) } }
                resp.close()
                adb.install(apk) { ok, msg ->
                    try { apk.delete() } catch (_: Exception) {}
                    reportInstall(ok, msg)
                }
            } catch (e: Exception) {
                reportInstall(false, e.message ?: "Install error")
            }
        }.start()
    }

    private fun reportInstall(ok: Boolean, msg: String) {
        send(JSONObject().put("type", "install-result").put("ok", ok).put("message", msg))
        val t = if (ok) "Update installed" else "Update failed: $msg"
        main.post { android.widget.Toast.makeText(this, t, android.widget.Toast.LENGTH_LONG).show() }
        AppState.update { message = if (ok) "Update installed." else "Update failed: $msg" }
    }

    private fun send(obj: JSONObject) { ws?.send(obj.toString()) }

    /** Called from the Allow / Deny buttons on the device screen. */
    fun respond(accept: Boolean) {
        if (accept) send(JSONObject().put("type", "accepted"))
        else { send(JSONObject().put("type", "denied")); AppState.update { phase = AppState.Phase.READY; message = "Ready" } }
    }

    // ---------- screen sharing (only after Allow + Android's Start now) ----------
    private fun startSharing(resultCode: Int, data: Intent) {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(resultCode, data)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { endSession("Screen sharing was stopped.") }
        }, main)

        val metrics = DisplayMetrics()
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        var w = metrics.widthPixels; var h = metrics.heightPixels
        val scale = 1280.0 / maxOf(w, h)
        if (scale < 1) { w = (w * scale).toInt() and 1.inv(); h = (h * scale).toInt() and 1.inv() }

        captureThread = HandlerThread("cap").apply { start() }
        val handler = Handler(captureThread!!.looper)
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        reader!!.setOnImageAvailableListener({ r -> onImage(r, w, h) }, handler)
        display = projection!!.createVirtualDisplay(
            "screen", w, h, metrics.densityDpi,
            android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader!!.surface, null, handler
        )
        AppState.update { phase = AppState.Phase.IN_SESSION; message = "A helper is connected" }
        send(JSONObject().put("type", "adb").put("ok", AppState.adbReady).put("message", AppState.adbMessage))
        showBanner()
    }

    private fun onImage(r: ImageReader, w: Int, h: Int) {
        val image = r.acquireLatestImage() ?: return
        try {
            val now = System.currentTimeMillis()
            if (now - lastFrame < frameGapMs) return
            lastFrame = now
            val plane = image.planes[0]
            val rowStride = plane.rowStride
            val bmp = Bitmap.createBitmap(rowStride / plane.pixelStride, h, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(plane.buffer)
            val out = ByteArrayOutputStream()
            Bitmap.createBitmap(bmp, 0, 0, w, h).compress(Bitmap.CompressFormat.JPEG, jpegQuality, out)
            bmp.recycle()
            ws?.send(out.toByteArray().toByteString())
        } catch (_: Exception) {
        } finally { image.close() }
    }

    private fun applyQuality(q: String) {
        when (q) {
            "low" -> { frameGapMs = 250; jpegQuality = 40 }
            "high" -> { frameGapMs = 100; jpegQuality = 65 }
            else -> { frameGapMs = 160; jpegQuality = 50 }
        }
    }

    private fun showBanner() = main.post {
        if (overlay != null) return@post
        if (!android.provider.Settings.canDrawOverlays(this)) return@post
        val tv = TextView(this).apply {
            text = "  ●  A helper is connected to this device — screen is being shared  "
            setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#CC000000"))
            setTypeface(Typeface.DEFAULT_BOLD); textSize = 14f
        }
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = 20 }
        try { wm.addView(tv, lp); overlay = tv } catch (_: Exception) {}
    }

    private fun hideBanner() = main.post {
        overlay?.let { try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } catch (_: Exception) {} }
        overlay = null
    }

    private fun endSession(reason: String) {
        hideBanner()
        try { display?.release() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        try { captureThread?.quitSafely() } catch (_: Exception) {}
        display = null; reader = null; projection = null; captureThread = null
        AppState.update {
            if (!stopped) { phase = AppState.Phase.READY; message = reason + "  Ready for next time." }
        }
    }

    override fun onDestroy() {
        stopped = true
        Holder.instance = null
        endSession("Closed.")
        ws?.close(1000, "closing")
        adb.close()
        super.onDestroy()
    }

    private fun deviceName(): String {
        val saved = getSharedPreferences("tvhelp", Context.MODE_PRIVATE).getString("name", null)
        return saved ?: (Build.MODEL ?: "device")
    }

    private fun notification(text: String): Notification {
        val mgr = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            mgr.createNotificationChannel(
                NotificationChannel(CHAN, "TV Help", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return Notification.Builder(this, CHAN)
            .setContentTitle("Help Tool")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val CHAN = "tvhelp"
        const val NOTE_ID = 42
        const val ACTION_START_SHARE = "start_share"
        const val ACTION_STOP = "stop"
        const val EXTRA_RESULT = "result"
        const val EXTRA_DATA = "data"
    }
}

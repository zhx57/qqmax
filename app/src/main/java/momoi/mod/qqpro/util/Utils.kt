package momoi.mod.qqpro.util

import android.annotation.SuppressLint
import android.app.Application
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.res.Resources
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.widget.Toast
import com.tencent.mobileqq.widget.QQToast
import com.tencent.qphone.base.util.QLog
import com.tencent.mobileqq.utils.TimeFormatterUtils
import androidx.core.net.toUri
import momoi.mod.qqpro.safeCacheDir

object Utils {
    @SuppressLint("PrivateApi")
    val application = Class.forName("android.app.ActivityThread").getMethod("currentApplication")
        .invoke(null) as Application
    val isDebug =
        try {
            val info = application.applicationInfo
            (info.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        } catch (e: Exception) {
            false
        }

    /**
     * Show QQ's native toast (QQToast) instead of Android's [Toast], whose layout
     * breaks under this watch ROM's ultra-large DPI.
     */
    fun toast(context: Context, text: CharSequence, longDuration: Boolean = false) {
        val duration = if (longDuration) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
        QQToast.i(context, text, duration).l()
    }

    /** Copy [text] to the system clipboard and show a native QQ toast (no Android toast). */
    fun copyToClipboard(context: Context, text: CharSequence, toastText: CharSequence = "已复制") {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("label", text))
        toast(context, toastText)
    }

    fun formatTime(timestamp: Long): CharSequence =
        TimeFormatterUtils.a(application, 3, timestamp, true, true)!!

    private var debugWatcher: Any? = null
    fun debugger(catch: Any?) {
        debugWatcher = catch
        Log.e("QQQQQQQQQQ", "debugger!")
    }

    /** The on-device debug log file ([log] appends here). Exposed so the debug menu can read it. */
    val debugLogFile by lazy {
        // externalCacheDir can be null on some ROMs (external storage unmounted); fall back
        // so the log still lands somewhere writable instead of a relative (unwritable) path.
        java.io.File(application.safeCacheDir, "qqpro_debug.log")
    }

    // ── Buffered log writing ─────────────────────────────────────────────────────────────────────
    // [log] used to do `debugLogFile.appendText(...)` per call: open → write → close, on the CALLING
    // thread. A busy session wrote thousands of lines (the unread/unread-breakdown paths alone emit
    // 20+ lines per kernel push), i.e. thousands of open/close cycles on watch flash — from binder
    // threads and the main thread alike. On cold start, where logging is most active, that I/O was
    // pure added latency.
    //
    // Lines are now accumulated under a lock and flushed in one write by a single shared timer.
    // Delivery is unchanged for a reader (same file, same "millis message" line format); the only
    // cost is that the last ≤[LOG_FLUSH_INTERVAL_MS] of lines may not be on disk yet — so anything
    // that must SEE the log (crash handler, debug export) calls [flushLog] first.
    private const val LOG_FLUSH_INTERVAL_MS = 500L
    private const val LOG_FLUSH_AT_CHARS = 16 * 1024

    private val logLock = Any()
    private val logBuffer = StringBuilder(LOG_FLUSH_AT_CHARS + 512)
    private var logFlushScheduled = false
    // Flush on a DEDICATED thread, not the main looper: the write is small but it is still flash
    // I/O, and [log] is called from binder threads and the UI thread during startup — the whole
    // point of buffering is to keep that off both.
    private val logFlushHandler by lazy {
        Handler(android.os.HandlerThread("qqpro-log-flush").apply { start() }.looper)
    }

    // Read the "启用日志" toggle straight from the qqpro prefs (not the Settings object) so logging
    // has no dependency on Settings init. SharedPreferences caches in memory, so this is cheap.
    private val proPrefs by lazy { application.getSharedPreferences("qqpro", Context.MODE_PRIVATE) }

    /** Logging is always on in debug builds; in release it requires the "启用日志" setting (default off). */
    val loggingEnabled: Boolean get() = isDebug || proPrefs.getBoolean("enableLog", false)

    /** Save the full on-device debug log file to the Downloads folder. Returns the saved location. */
    fun saveLogToDownloads(): momoi.mod.qqpro.watchdog.LogExporter.Saved? {
        flushLog()
        return momoi.mod.qqpro.watchdog.LogExporter.saveFile(application, "qqpro_debug", debugLogFile, "log")
    }

    fun log(msg: String) {
        // Always log in debug builds; in release only when the user enabled it (default off).
        if (!loggingEnabled) return
        Log.e("QQ Max", msg)
        // This watch ROM strips app android.util.Log; QLog reliably reaches logcat
        try {
            QLog.e("QQ Max", 1, msg)
        } catch (e: Throwable) {
        }
        // Persist to a file we can `adb pull` regardless of logcat gating — buffered, see above.
        val line = StringBuilder(msg.length + 16).append(System.currentTimeMillis()).append(' ').append(msg).append('\n')
        synchronized(logLock) {
            logBuffer.append(line)
            if (logBuffer.length >= LOG_FLUSH_AT_CHARS) {
                flushLogLocked()
            } else if (!logFlushScheduled) {
                logFlushScheduled = true
                runCatching {
                    logFlushHandler.postDelayed({ flushLog() }, LOG_FLUSH_INTERVAL_MS)
                }.onFailure { logFlushScheduled = false }
            }
        }
    }

    /** Write any buffered log lines to disk now (also disarms the pending flush). */
    fun flushLog() {
        synchronized(logLock) {
            logFlushScheduled = false
            flushLogLocked()
        }
    }

    private fun flushLogLocked() {
        if (logBuffer.isEmpty()) return
        val out = logBuffer.toString()
        logBuffer.setLength(0)
        try {
            debugLogFile.appendText(out)
        } catch (e: Throwable) {
        }
    }

    val heightPixels = Resources.getSystem().displayMetrics.heightPixels
    val isRoundScreen = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        Resources.getSystem().configuration.isScreenRound
    } else {
        isDebug
    }

    fun openUrl(url: String) {
        val normalized = if (url.contains("://")) url else "https://$url"
        val intent = Intent(Intent.ACTION_VIEW, normalized.toUri())
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(application.packageManager) != null) {
            application.startActivity(intent)
        }
    }

    /** Open the system dialer prefilled with [number] (does not place the call automatically). */
    fun dialNumber(number: String) {
        val intent = Intent(Intent.ACTION_DIAL, "tel:$number".toUri())
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(application.packageManager) != null) {
            application.startActivity(intent)
        } else {
            toast(application, "无法拨号")
        }
    }
}
package momoi.mod.qqpro.util

import android.app.Application
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log

/**
 * Cold-start timing marks.
 *
 * ## Why this does NOT go through [Utils.log]
 * [Utils.log] is gated by the "启用日志" setting (and by `isDebug`). Instrumenting startup behind the
 * same switch you are trying to measure is self-defeating: the first field logs captured with the
 * switch OFF contained no marks at all, and the ones captured after it was turned on still had none,
 * because the process had already been started before the switch flipped. So [mark] writes
 * unconditionally — to logcat under its own tag, and to its own small file — and never calls Utils
 * (which would also recurse if it were ever marked from Utils' own initialisation).
 *
 * ## Cost
 * A mark costs one `Log.e`, one `QLog.e` and one small file append, and there are at most
 * [MAX_MARKS] of them per process (first occurrence of each label only). That is a handful of lines
 * for a whole launch — nothing next to what it measures.
 *
 * ## Reading it
 * `Boot: +1234ms from process start (+56ms from first mark) | label`
 * The first number is the real cold-start position of that label, measured from the moment the
 * system forked the process.
 *
 * The file is `<cache>/qqpro_boot.log` and is also shown in the in-app debug menu, so no adb and no
 * logcat is needed to read it.
 */
object Boot {
    private const val TAG = "QQMax-Boot"
    private const val MAX_MARKS = 24

    /** Uptime at which this process was started (0 when the API can't tell us). */
    private val processStartUptime: Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching { Process.getStartUptimeMillis() }.getOrDefault(0L)
        } else {
            0L
        }

    /** When [Boot] was first touched — a rough "our code started running" reference. */
    private val firstTouchUptime = SystemClock.uptimeMillis()

    private val seen = HashSet<String>()
    private var headerWritten = false

    /**
     * The boot log file, resolved WITHOUT touching [Utils]: Utils holds the app context via
     * reflection and marking from its initialiser would re-enter it.
     */
    val file: java.io.File? by lazy {
        runCatching {
            val app = Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication")
                .invoke(null) as? Application
            val dir = app?.cacheDir ?: return@runCatching null
            java.io.File(dir, "qqpro_boot.log")
        }.getOrNull()
    }

    fun mark(label: String) {
        val now = SystemClock.uptimeMillis()
        val accepted = synchronized(seen) {
            if (seen.size >= MAX_MARKS) false else seen.add(label)
        }
        if (!accepted) return
        val sinceProcess = if (processStartUptime > 0L) "${now - processStartUptime}ms" else "?"
        val line = "Boot: +$sinceProcess from process start " +
            "(+${now - firstTouchUptime}ms from first mark) | $label"
        runCatching { Log.e(TAG, line) }
        runCatching { com.tencent.qphone.base.util.QLog.e(TAG, 1, line) }
        runCatching {
            val f = file ?: return@runCatching
            if (!headerWritten) {
                headerWritten = true
                f.appendText("\n=== run pid=${Process.myPid()} ${java.util.Date()} ===\n")
            }
            f.appendText("${System.currentTimeMillis()} $line\n")
        }
    }
}

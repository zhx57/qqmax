package momoi.mod.qqpro.util

import android.os.Build
import android.os.Process
import android.os.SystemClock

/**
 * Cold-start timing marks.
 *
 * The point of this object is to make "启动慢" a measurement instead of a guess. Every mark logs the
 * elapsed time since the PROCESS started (not since the first mark), so one cold-start log answers
 * "how much of the startup is ours" without needing a profiler on the watch.
 *
 * Cost control, because this runs on the startup path:
 *  - each label is recorded ONCE per process (the first occurrence wins), so a hot path that calls
 *    [mark] on every bind/frame does not turn into a log storm;
 *  - nothing is formatted or logged unless logging is enabled (release defaults to off);
 *  - [Process.getStartUptimeMillis] is a single cheap system call, taken once.
 *
 * Read the output as: `Boot: +1234ms from process start (+56ms from first mark) | label`
 * — the first number is the real cold-start position of that label.
 */
object Boot {
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

    fun mark(label: String) {
        val now = SystemClock.uptimeMillis()
        val first = synchronized(seen) { seen.add(label) }
        if (!first) return
        if (!Utils.loggingEnabled) return
        val sinceProcess = if (processStartUptime > 0L) "${now - processStartUptime}ms" else "?"
        val sinceFirstTouch = "${now - firstTouchUptime}ms"
        Utils.log("Boot: +$sinceProcess from process start (+$sinceFirstTouch from first mark) | $label")
    }
}

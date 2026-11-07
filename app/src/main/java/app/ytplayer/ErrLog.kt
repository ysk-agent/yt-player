package app.ytplayer

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tiny on-device error log (v0.1.1 diagnostics).
 *
 * The app has no logcat channel back to the developer, so caught failures are
 * appended to errors.log in the internal files dir, and uncaught exceptions
 * are persisted to last-crash.txt by the handler YtApplication installs.
 * Everything here is best-effort and never throws: diagnostics must never
 * mask or cause the original failure.
 */
object ErrLog {

    const val ERRORS_FILE = "errors.log"
    const val CRASH_FILE = "last-crash.txt"
    private const val MAX_ERRORS_BYTES = 128 * 1024L

    fun note(context: Context, where: String, message: String) {
        try {
            val file = File(context.applicationContext.filesDir, ERRORS_FILE)
            if (file.length() > MAX_ERRORS_BYTES) file.writeText("")
            file.appendText("${now()} [$where] $message\n")
        } catch (e: Exception) {
            // Diagnostics must never mask the original failure.
        }
    }

    fun record(context: Context, where: String, error: Throwable) {
        try {
            val file = File(context.applicationContext.filesDir, ERRORS_FILE)
            if (file.length() > MAX_ERRORS_BYTES) file.writeText("")
            file.appendText(buildString {
                append(now()).append(" [").append(where).append("] ")
                append(error.javaClass.name).append(": ").append(error.message).append('\n')
                append(stackOf(error)).append('\n')
            })
        } catch (e: Exception) {
            // Diagnostics must never mask the original failure.
        }
    }

    /** Full persisted crash report, or null if there has been no uncaught crash. */
    fun readCrash(context: Context): String? {
        return try {
            val file = File(context.applicationContext.filesDir, CRASH_FILE)
            if (file.exists()) file.readText() else null
        } catch (e: Exception) {
            null
        }
    }

    /** Tail of errors.log: at most the last [maxLines] lines and last [maxChars] chars. */
    fun errorsTail(context: Context, maxLines: Int = 200, maxChars: Int = 32 * 1024): String {
        return try {
            val file = File(context.applicationContext.filesDir, ERRORS_FILE)
            if (!file.exists()) return "(none)"
            val lines = file.readLines()
            val tail = lines.takeLast(maxLines).joinToString("\n")
            if (tail.length <= maxChars) tail else "…" + tail.takeLast(maxChars)
        } catch (e: Exception) {
            "(unreadable: ${e.message})"
        }
    }

    fun stackOf(error: Throwable): String {
        val writer = StringWriter()
        error.printStackTrace(PrintWriter(writer))
        return writer.toString()
    }

    fun now(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    fun appVersion(context: Context): String {
        return try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            "${info.versionName} (code ${info.longVersionCode})"
        } catch (e: Exception) {
            "unknown"
        }
    }
}

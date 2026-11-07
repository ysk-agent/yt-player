package app.ytplayer

import android.app.Application
import android.os.Build
import java.io.File

/**
 * Diagnostics bootstrap (v0.1.1).
 *
 * Installs the uncaught-exception handler as early as possible, and — if the
 * previous process left a crash behind and a logs folder was picked — exports
 * it immediately. That second step is what makes even a launch crash-loop
 * diagnosable: the NEXT process start ships the previous crash out before any
 * activity runs. Everything is wrapped so the diagnostic code itself can
 * never crash startup.
 */
class YtApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        try {
            installCrashHandler()
        } catch (t: Throwable) {
            // Never take startup down for diagnostics.
        }
        try {
            exportPreviousCrashIfAny()
        } catch (t: Throwable) {
            // Same: best effort only.
        }
    }

    private fun installCrashHandler() {
        val appContext = applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val text = buildString {
                    append("YT Player uncaught crash\n")
                    append("time: ").append(ErrLog.now()).append('\n')
                    append("thread: ").append(thread.name).append('\n')
                    append("app: ").append(ErrLog.appVersion(appContext)).append('\n')
                    append("device: ").append(Build.MANUFACTURER).append(' ')
                        .append(Build.MODEL).append(", Android ")
                        .append(Build.VERSION.RELEASE)
                        .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
                    append('\n')
                    append(ErrLog.stackOf(error))
                }
                File(appContext.filesDir, ErrLog.CRASH_FILE).writeText(text)
                // External-files mirror (readable without root via USB, and
                // picked up by the same folder as the log exports).
                try {
                    val ext = appContext.getExternalFilesDir(null)
                    if (ext != null) File(ext, ErrLog.CRASH_FILE).writeText(text)
                } catch (e: Exception) {
                    // Mirror is best effort.
                }
            } catch (e: Exception) {
                // Diagnostics must never mask the original failure.
            }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun exportPreviousCrashIfAny() {
        val crash = File(applicationContext.filesDir, ErrLog.CRASH_FILE)
        if (!crash.exists()) return
        if (!LogExport.hasDestination(applicationContext)) return
        val appContext = applicationContext
        Thread {
            try {
                LogExport.export(appContext)
            } catch (t: Throwable) {
                // Best effort; the manual "Export logs now" button remains.
            }
        }.start()
    }
}

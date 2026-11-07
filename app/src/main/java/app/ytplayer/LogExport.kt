package app.ytplayer

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Debug-log export (v0.1.1 diagnostics), adapted from phone-node's LogExport.
 *
 * The user picks a folder once through the system folder picker (SAF) — the
 * intended target is the Drive folder yt-player/logs — and each export writes
 * one small timestamped text file there, plus a mirror under the app's
 * external files dir. The app holds no Google credentials, only the persisted
 * folder permission. Safe to call from any thread; never throws.
 */
object LogExport {

    private const val PREFS = "ytplayer-prefs"
    private const val KEY_TREE_URI = "log_tree_uri"

    fun setTreeUri(context: Context, uri: Uri) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_TREE_URI, uri.toString()).apply()
    }

    fun treeUri(context: Context): Uri? {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TREE_URI, null)
        return stored?.let { Uri.parse(it) }
    }

    fun hasDestination(context: Context): Boolean = treeUri(context) != null

    /** Writes one bundle file. Returns true if it reached the SAF folder. */
    fun export(context: Context): Boolean {
        val appContext = context.applicationContext
        var reachedFolder = false
        try {
            val bundle = buildBundle(appContext)
            val name = "ytplayer-log-" +
                SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".txt"

            // Local mirror (Android/data/<pkg>/files/logs/).
            try {
                val dir = File(appContext.getExternalFilesDir(null), "logs")
                dir.mkdirs()
                File(dir, name).writeText(bundle)
                File(dir, "latest.txt").writeText(bundle)
            } catch (e: Exception) {
                // Mirror is best effort.
            }

            val tree = treeUri(appContext)
            if (tree != null) {
                val resolver = appContext.contentResolver
                val parentUri = DocumentsContract.buildDocumentUriUsingTree(
                    tree, DocumentsContract.getTreeDocumentId(tree)
                )
                val docUri = DocumentsContract.createDocument(
                    resolver, parentUri, "text/plain", name
                )
                if (docUri != null) {
                    resolver.openOutputStream(docUri, "w")?.use { out ->
                        out.write(bundle.toByteArray(Charsets.UTF_8))
                    }
                    reachedFolder = true
                }
            }
        } catch (t: Throwable) {
            ErrLog.record(appContext, "log-export", t)
        }
        return reachedFolder
    }

    private fun buildBundle(context: Context): String {
        return buildString {
            append("YT Player debug log\n")
            append("exported: ").append(ErrLog.now()).append('\n')
            append("device: ").append(Build.MANUFACTURER).append(' ')
                .append(Build.MODEL).append(", Android ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
            append("app: ").append(ErrLog.appVersion(context)).append('\n')
            append('\n')
            append("===== state =====\n")
            try {
                val db = YtDb(context)
                val level = db.getSetting("speed_level")?.toIntOrNull() ?: 0
                append("videos total: ").append(db.countVisible(null)).append('\n')
                append("unplayed: ").append(db.countVisible("unplayed"))
                    .append(", in progress: ").append(db.countVisible("playing"))
                    .append(", played: ").append(db.countVisible("played")).append('\n')
                append("queue length: ").append(db.loadQueueIds().size)
                    .append(", current index: ").append(db.loadCurrentIndex()).append('\n')
                append("speed level: ").append(level)
                    .append(" (rate ").append(SpeedController.rateForLevel(level))
                    .append("x)\n")
            } catch (t: Throwable) {
                append("(state unreadable: ").append(t.message).append(")\n")
            }
            append('\n')
            append("===== last crash =====\n")
            append(ErrLog.readCrash(context) ?: "(none)").append('\n')
            append('\n')
            append("===== errors.log (tail) =====\n")
            append(ErrLog.errorsTail(context)).append('\n')
        }
    }
}

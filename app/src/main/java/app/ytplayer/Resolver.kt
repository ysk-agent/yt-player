package app.ytplayer

import android.content.Context
import android.net.Uri

/**
 * Turns a stored library item into a playable URI at play start.
 *
 * v0.1: local items resolve to their stored document/content URI (with an
 * existence check), stream items resolve to their stored URL directly. The
 * yt-dlp backend resolve endpoint will slot in here later without touching
 * the player or the library model.
 */
class Resolver(private val context: Context) {

    /** Returns the playable URI, or null if the item can no longer be played
     *  (e.g. a local file that has been removed outside the app). */
    fun resolve(item: VideoItem): Uri? {
        return when (item.sourceKind) {
            "local" -> {
                val uri = Uri.parse(item.locator)
                if (exists(uri)) uri else null
            }
            else -> Uri.parse(item.locator) // "stream"
        }
    }

    private fun exists(uri: Uri): Boolean {
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
        } catch (e: Exception) {
            false
        }
    }
}

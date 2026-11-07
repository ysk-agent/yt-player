package app.ytplayer

import android.content.ComponentName
import android.content.Context
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture

object ControllerHelper {

    fun connect(context: Context): ListenableFuture<MediaController> {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        return MediaController.Builder(context, token).buildAsync()
    }

    fun release(future: ListenableFuture<MediaController>) {
        MediaController.releaseFuture(future)
    }

    /**
     * Sets [items] (in display order) as the queue on the shared player, persists
     * the queue to SQLite, and starts [startIndex] at its saved position.
     * Items the resolver can no longer resolve are skipped with a notice.
     */
    fun playQueue(
        context: Context,
        db: YtDb,
        controller: MediaController,
        items: List<VideoItem>,
        startIndex: Int
    ) {
        val resolver = Resolver(context)
        val playable = mutableListOf<VideoItem>()
        val mediaItems = mutableListOf<MediaItem>()
        var skipped = 0
        for (item in items) {
            val uri = resolver.resolve(item)
            if (uri == null) {
                skipped++
                continue
            }
            playable.add(item)
            mediaItems.add(
                MediaItem.Builder()
                    .setMediaId(item.id.toString())
                    .setUri(uri)
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(item.title).build())
                    .build()
            )
        }
        if (mediaItems.isEmpty()) {
            Toast.makeText(context, "Nothing playable (files missing?)", Toast.LENGTH_LONG).show()
            return
        }
        if (skipped > 0) {
            Toast.makeText(context, "Skipped $skipped missing file(s)", Toast.LENGTH_SHORT).show()
        }
        val startItem = items.getOrNull(startIndex)
        val newStart = playable.indexOfFirst { it.id == startItem?.id }
            .let { if (it >= 0) it else 0 }
        val startPos = playable[newStart].let {
            if (it.status == "played") 0L else it.positionMs
        }
        db.saveQueue(playable.map { it.id }, newStart)
        controller.setMediaItems(mediaItems, newStart, startPos)
        controller.prepare()
        controller.play()
    }
}

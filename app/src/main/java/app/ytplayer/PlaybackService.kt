package app.ytplayer

import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Owns the single ExoPlayer for the whole app. The library and player screens
 * connect through a MediaController, so the same player (and the same position,
 * speed and queue) is shared by the video screen, PiP and audio-background
 * playback. Mode switches never re-prepare the player.
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private lateinit var db: YtDb
    private val handler = Handler(Looper.getMainLooper())

    private val saveRunnable = object : Runnable {
        override fun run() {
            persistProgress()
            handler.postDelayed(this, SAVE_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        db = YtDb(this)

        val player = ExoPlayer.Builder(this).build()
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val id = mediaItem?.mediaId?.toLongOrNull() ?: return
                db.notePlayStarted(id)
                persistIndex()
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    currentVideoId()?.let { db.markPlayed(it) }
                }
                if (state == Player.STATE_READY || state == Player.STATE_ENDED) {
                    persistProgress()
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) persistProgress()
            }
        })

        mediaSession = MediaSession.Builder(this, player).build()

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setNotificationId(1001)
                .setChannelId(NOTIFICATION_CHANNEL_ID)
                .setChannelName(R.string.notification_channel_name)
                .build()
        )

        restoreState(player)
        handler.postDelayed(saveRunnable, SAVE_INTERVAL_MS)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onDestroy() {
        handler.removeCallbacks(saveRunnable)
        persistProgress()
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    private fun currentVideoId(): Long? =
        mediaSession?.player?.currentMediaItem?.mediaId?.toLongOrNull()

    private fun persistIndex() {
        val player = mediaSession?.player ?: return
        val ids = db.loadQueueIds()
        if (ids.isNotEmpty()) db.saveQueue(ids, player.currentMediaItemIndex)
    }

    private fun persistProgress() {
        val player = mediaSession?.player ?: return
        val id = currentVideoId() ?: return
        db.saveProgress(id, player.currentPosition)
        // Learn durations from the player for items probing could not fill in.
        val dur = player.duration
        if (dur > 0) db.setDuration(id, dur)
        db.setSetting("speed_level", SpeedController.level.toString())
    }

    /** Rebuilds queue / position / speed from SQLite so reopening the app (or the
     *  service being restarted) resumes where the user left off. */
    private fun restoreState(player: ExoPlayer) {
        val level = db.getSetting("speed_level")?.toIntOrNull() ?: 0
        val rate = SpeedController.setLevel(level)
        player.setPlaybackSpeed(rate)

        val ids = db.loadQueueIds()
        if (ids.isEmpty()) return
        val resolver = Resolver(this)
        val items = mutableListOf<MediaItem>()
        val keptIds = mutableListOf<Long>()
        for (id in ids) {
            val item = db.getById(id) ?: continue
            val uri = resolver.resolve(item) ?: continue
            items.add(
                MediaItem.Builder()
                    .setMediaId(item.id.toString())
                    .setUri(uri)
                    .setMediaMetadata(
                        MediaMetadata.Builder().setTitle(item.title).build()
                    )
                    .build()
            )
            keptIds.add(id)
        }
        if (items.isEmpty()) return
        val index = db.loadCurrentIndex().coerceIn(0, items.lastIndex)
        val startItem = db.getById(keptIds[index])
        val startPos = if (startItem?.status == "played") 0L else (startItem?.positionMs ?: 0L)
        player.setMediaItems(items, index, startPos)
        player.prepare()
        player.playWhenReady = false
    }

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "ytplayer_playback"
        private const val SAVE_INTERVAL_MS = 5_000L
    }
}

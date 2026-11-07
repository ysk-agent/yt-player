package app.ytplayer

import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.ToggleButton
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.session.MediaController
import androidx.media3.ui.PlayerView
import com.google.common.util.concurrent.ListenableFuture
import java.util.Locale

class PlayerActivity : AppCompatActivity() {

    private lateinit var db: YtDb
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    private lateinit var playerView: PlayerView
    private lateinit var controlsBox: LinearLayout
    private lateinit var seekBar: SeekBar
    private var seeking = false

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            updateUi()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        InsetsHelper.applyToContentRoot(this)
        db = YtDb(this)

        playerView = findViewById(R.id.playerView)
        playerView.useController = false
        controlsBox = findViewById(R.id.controlsBox)
        seekBar = findViewById(R.id.seekBar)

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(bar: SeekBar?) {
                seeking = true
            }

            override fun onStopTrackingTouch(bar: SeekBar?) {
                seeking = false
                controller?.seekTo((bar?.progress ?: 0) * 1000L)
            }
        })

        findViewById<Button>(R.id.btnPlayPause).setOnClickListener {
            val c = controller ?: return@setOnClickListener
            if (c.isPlaying) c.pause() else c.play()
        }
        findViewById<Button>(R.id.btnBack10).setOnClickListener {
            val c = controller ?: return@setOnClickListener
            c.seekTo(maxOf(0L, c.currentPosition - 10_000L))
        }
        findViewById<Button>(R.id.btnFwd30).setOnClickListener {
            val c = controller ?: return@setOnClickListener
            c.seekTo(c.currentPosition + 30_000L)
        }
        findViewById<Button>(R.id.btnPrev).setOnClickListener { controller?.seekToPrevious() }
        findViewById<Button>(R.id.btnNext).setOnClickListener { controller?.seekToNext() }
        findViewById<Button>(R.id.btnPip).setOnClickListener { enterPip() }

        // Audio-only mode: disable the video track on the SAME player. Playback
        // (position, speed, queue) continues untouched; the surface just stops
        // receiving video.
        findViewById<ToggleButton>(R.id.btnAudioOnly).setOnCheckedChangeListener { _, checked ->
            val c = controller ?: return@setOnCheckedChangeListener
            c.trackSelectionParameters = c.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, checked)
                .build()
        }

        // Speed: logarithmic level API. Slider is linear in level.
        val level = db.getSetting("speed_level")?.toIntOrNull() ?: 0
        val rate = SpeedController.setLevel(level)
        val slider = findViewById<SeekBar>(R.id.speedSlider)
        slider.max = SpeedController.MAX_LEVEL - SpeedController.MIN_LEVEL
        slider.progress = SpeedController.level - SpeedController.MIN_LEVEL
        updateSpeedLabel(rate)
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) applySpeedLevel(progress + SpeedController.MIN_LEVEL)
            }

            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })
        findViewById<Button>(R.id.btnSpeedMinus).setOnClickListener {
            applySpeedLevel(SpeedController.level - 1)
            slider.progress = SpeedController.level - SpeedController.MIN_LEVEL
        }
        findViewById<Button>(R.id.btnSpeedPlus).setOnClickListener {
            applySpeedLevel(SpeedController.level + 1)
            slider.progress = SpeedController.level - SpeedController.MIN_LEVEL
        }
    }

    private fun applySpeedLevel(level: Int) {
        val rate = SpeedController.setLevel(level)
        controller?.setPlaybackSpeed(rate)
        db.setSetting("speed_level", SpeedController.level.toString())
        updateSpeedLabel(rate)
    }

    private fun updateSpeedLabel(rate: Float) {
        findViewById<TextView>(R.id.speedLabel).text =
            String.format(Locale.US, "%.2fx", rate)
    }

    override fun onStart() {
        super.onStart()
        controllerFuture = ControllerHelper.connect(this).also { future ->
            future.addListener({
                try {
                    val c = future.get()
                    controller = c
                    playerView.player = c
                    c.setPlaybackSpeed(SpeedController.currentRate())
                } catch (e: Exception) {
                    controller = null
                }
            }, ContextCompat.getMainExecutor(this))
        }
        handler.post(ticker)
    }

    override fun onStop() {
        handler.removeCallbacks(ticker)
        playerView.player = null
        controller = null
        controllerFuture?.let { ControllerHelper.release(it) }
        controllerFuture = null
        super.onStop()
    }

    private fun updateUi() {
        val c = controller ?: return
        val title = c.mediaMetadata.title?.toString()
            ?: c.currentMediaItem?.mediaId?.let { db.getById(it.toLongOrNull() ?: -1)?.title }
            ?: ""
        findViewById<TextView>(R.id.nowTitle).text = title
        val dur = c.duration
        val pos = c.currentPosition
        if (!seeking && dur > 0) {
            seekBar.max = (dur / 1000).toInt()
            seekBar.progress = (pos / 1000).toInt()
        }
        findViewById<TextView>(R.id.timeLabel).text =
            "${formatTime(pos)} / ${formatTime(dur)}"
        findViewById<Button>(R.id.btnPlayPause).text = if (c.isPlaying) "Pause" else "Play"
        // Keep the speed slider in sync if the level changed elsewhere.
        val slider = findViewById<SeekBar>(R.id.speedSlider)
        val want = SpeedController.level - SpeedController.MIN_LEVEL
        if (slider.progress != want) slider.progress = want
        updateSpeedLabel(SpeedController.currentRate())
    }

    private fun formatTime(ms: Long): String {
        if (ms < 0) return "0:00"
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    private fun enterPip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()
            enterPictureInPictureMode(params)
        } catch (e: Exception) {
            // PiP unavailable on this device/state; stay in the normal screen.
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Leaving the app while a video plays -> continue seamlessly in PiP.
        val c = controller ?: return
        if (c.isPlaying && c.mediaItemCount > 0) enterPip()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        controlsBox.visibility = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
    }
}

package app.ytplayer

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import com.google.common.util.concurrent.ListenableFuture

class QueueActivity : AppCompatActivity() {

    private lateinit var db: YtDb
    private var queueItems: List<VideoItem> = emptyList()
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_queue)
        InsetsHelper.applyToContentRoot(this)
        db = YtDb(this)

        val ids = db.loadQueueIds()
        queueItems = ids.mapNotNull { db.getById(it) }
        val currentIndex = db.loadCurrentIndex()

        val labels = queueItems.mapIndexed { i, item ->
            val marker = if (i == currentIndex) "▶ " else ""
            "$marker${item.title}"
        }
        val list = findViewById<ListView>(R.id.queueList)
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        if (queueItems.isEmpty()) {
            Toast.makeText(this, "Queue is empty — tap a library item first", Toast.LENGTH_LONG)
                .show()
        }
        list.setOnItemClickListener { _, _, position, _ ->
            val c = controller
            if (c != null && position < c.mediaItemCount) {
                c.seekTo(position, 0L)
                c.play()
                finish()
            } else {
                Toast.makeText(this, "Player not ready yet", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        controllerFuture = ControllerHelper.connect(this).also { future ->
            future.addListener({
                try {
                    controller = future.get()
                } catch (e: Exception) {
                    controller = null
                }
            }, ContextCompat.getMainExecutor(this))
        }
    }

    override fun onStop() {
        controller = null
        controllerFuture?.let { ControllerHelper.release(it) }
        controllerFuture = null
        super.onStop()
    }
}

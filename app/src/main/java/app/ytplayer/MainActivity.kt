package app.ytplayer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.media3.session.MediaController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.ChipGroup
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var db: YtDb
    private lateinit var adapter: VideoAdapter
    private var items: List<VideoItem> = emptyList()
    private var currentFilter = "all"
    private val selected = mutableSetOf<Long>()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    private val io = Executors.newSingleThreadExecutor()

    private val openTree = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                contentResolver.takePersistableUriPermission(uri, flags)
            } catch (e: Exception) {
                // Non-fatal: files may become inaccessible after reboot.
            }
            Toast.makeText(this, "Scanning folder…", Toast.LENGTH_SHORT).show()
            io.execute { scanTree(uri) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        db = YtDb(this)

        adapter = VideoAdapter()
        val list = findViewById<RecyclerView>(R.id.videoList)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        findViewById<Button>(R.id.btnAddFolder).setOnClickListener { openTree.launch(null) }
        findViewById<Button>(R.id.btnAddStream).setOnClickListener { showAddStreamDialog() }
        findViewById<Button>(R.id.btnPlayer).setOnClickListener {
            startActivity(Intent(this, PlayerActivity::class.java))
        }
        findViewById<Button>(R.id.btnQueue).setOnClickListener {
            startActivity(Intent(this, QueueActivity::class.java))
        }
        findViewById<Button>(R.id.btnDeleteSelected).setOnClickListener { deleteSelected() }

        findViewById<ChipGroup>(R.id.filterChips).setOnCheckedChangeListener { _, checkedId ->
            currentFilter = when (checkedId) {
                R.id.chipUnplayed -> "unplayed"
                R.id.chipPlaying -> "playing"
                R.id.chipPlayed -> "played"
                else -> "all"
            }
            selected.clear()
            reload()
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        reload()
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

    override fun onResume() {
        super.onResume()
        if (::db.isInitialized) reload()
    }

    private fun reload() {
        items = db.listVideos(currentFilter)
        adapter.notifyDataSetChanged()
        updateDeleteBar()
    }

    private fun updateDeleteBar() {
        findViewById<Button>(R.id.btnDeleteSelected).visibility =
            if (selected.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun scanTree(treeUri: Uri) {
        val root = DocumentFile.fromTreeUri(this, treeUri) ?: return
        var added = 0
        val stack = ArrayDeque<DocumentFile>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            for (child in dir.listFiles()) {
                if (child.isDirectory) {
                    stack.add(child)
                } else if (child.isFile && isMediaFile(child.name)) {
                    val name = child.name ?: continue
                    val title = name.substringBeforeLast('.', name)
                    val id = db.register(title, "local", child.uri.toString(), name)
                    if (id > 0) {
                        added++
                        probeDuration(id, child.uri)
                    }
                }
            }
        }
        runOnUiThread {
            Toast.makeText(this, "Scan done ($added files registered)", Toast.LENGTH_SHORT).show()
            reload()
        }
    }

    private fun probeDuration(id: Long, uri: Uri) {
        try {
            val mmr = MediaMetadataRetriever()
            mmr.setDataSource(this, uri)
            val dur = mmr.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L
            mmr.release()
            if (dur > 0) db.setDuration(id, dur)
        } catch (e: Exception) {
            // Duration stays 0; the player fills it in on first playback.
        }
    }

    private fun showAddStreamDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 8)
        }
        val urlInput = EditText(this).apply { hint = "Stream URL (https://…)" }
        val titleInput = EditText(this).apply { hint = "Title (optional)" }
        box.addView(urlInput)
        box.addView(titleInput)
        AlertDialog.Builder(this)
            .setTitle("Add stream")
            .setView(box)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Add") { _, _ ->
                val url = urlInput.text.toString().trim()
                if (url.isEmpty()) {
                    Toast.makeText(this, "URL is empty", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val title = titleInput.text.toString().trim()
                    .ifEmpty { url.substringAfterLast('/').ifEmpty { url } }
                db.register(title, "stream", url, null)
                reload()
            }
            .show()
    }

    private fun deleteSelected() {
        val toDelete = items.filter { it.id in selected }
        if (toDelete.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("Delete ${toDelete.size} item(s)?")
            .setMessage("Local files are deleted where possible; entries are hidden either way. This is your call, item by item — nothing is deleted automatically.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                io.execute {
                    for (item in toDelete) {
                        if (item.sourceKind == "local") {
                            try {
                                DocumentsContract.deleteDocument(
                                    contentResolver, Uri.parse(item.locator)
                                )
                            } catch (e: Exception) {
                                // Tolerate failure (e.g. permission); entry is still hidden.
                            }
                        }
                    }
                    db.softDelete(toDelete.map { it.id })
                    runOnUiThread {
                        selected.clear()
                        reload()
                    }
                }
            }
            .show()
    }

    private fun playItem(item: VideoItem) {
        val c = controller
        if (c == null) {
            Toast.makeText(this, "Player not ready yet, try again", Toast.LENGTH_SHORT).show()
            return
        }
        val idx = items.indexOfFirst { it.id == item.id }
        ControllerHelper.playQueue(this, db, c, items, if (idx >= 0) idx else 0)
        startActivity(Intent(this, PlayerActivity::class.java))
    }

    private fun isMediaFile(name: String?): Boolean {
        if (name == null) return false
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in setOf("mp4", "mkv", "webm", "m4a", "mp3", "opus")
    }

    private fun formatDuration(ms: Long): String {
        if (ms <= 0) return "--:--"
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    inner class VideoAdapter : RecyclerView.Adapter<VideoAdapter.Holder>() {

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val check: CheckBox = view.findViewById(R.id.itemCheck)
            val title: TextView = view.findViewById(R.id.itemTitle)
            val badge: TextView = view.findViewById(R.id.itemBadge)
            val meta: TextView = view.findViewById(R.id.itemMeta)
            val progress: ProgressBar = view.findViewById(R.id.itemProgress)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_video, parent, false)
            return Holder(view)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            holder.title.text = item.title
            holder.badge.text = if (item.sourceKind == "local") "LOCAL" else "STREAM"
            val statusText = when (item.status) {
                "played" -> "Played"
                "playing" -> "In progress"
                else -> "Unplayed"
            }
            holder.meta.text = "${formatDuration(item.durationMs)} · $statusText"
            holder.progress.progress = when {
                item.durationMs > 0 && item.status == "played" -> 100
                item.durationMs > 0 -> (item.positionMs * 100 / item.durationMs).toInt()
                else -> 0
            }
            val inSelectionMode = selected.isNotEmpty()
            holder.check.visibility = if (inSelectionMode) View.VISIBLE else View.GONE
            holder.check.isChecked = item.id in selected
            holder.itemView.setBackgroundColor(
                if (item.id in selected) 0xFFE0E0E0.toInt() else 0x00000000
            )
            holder.itemView.setOnClickListener {
                if (selected.isNotEmpty()) {
                    toggleSelect(item)
                } else {
                    playItem(item)
                }
            }
            holder.itemView.setOnLongClickListener {
                toggleSelect(item)
                true
            }
            holder.check.setOnClickListener { toggleSelect(item) }
        }

        private fun toggleSelect(item: VideoItem) {
            if (item.id in selected) selected.remove(item.id) else selected.add(item.id)
            notifyDataSetChanged()
            updateDeleteBar()
        }
    }
}

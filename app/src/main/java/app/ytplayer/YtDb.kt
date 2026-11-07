package app.ytplayer

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray

data class VideoItem(
    val id: Long,
    val videoKey: String,
    val title: String,
    val channel: String?,
    val sourceKind: String, // "local" | "stream"
    val locator: String,    // document/content URI for local, URL for stream
    val fileName: String?,
    val durationMs: Long,
    val addedAt: Long,
    val publishedAt: Long?,
    val status: String,     // "unplayed" | "playing" | "played"
    val positionMs: Long,
    val playCount: Int,
    val lastPlayedAt: Long?,
    val completedAt: Long?,
    val deletedAt: Long?
)

class YtDb(context: Context) : SQLiteOpenHelper(context, "ytplayer.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE videos(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                video_key TEXT UNIQUE NOT NULL,
                title TEXT NOT NULL,
                channel TEXT,
                source_kind TEXT NOT NULL,
                locator TEXT NOT NULL,
                file_name TEXT,
                duration_ms INTEGER NOT NULL DEFAULT 0,
                added_at INTEGER NOT NULL,
                published_at INTEGER,
                status TEXT NOT NULL DEFAULT 'unplayed',
                position_ms INTEGER NOT NULL DEFAULT 0,
                play_count INTEGER NOT NULL DEFAULT 0,
                last_played_at INTEGER,
                completed_at INTEGER,
                deleted_at INTEGER
            )"""
        )
        db.execSQL("CREATE TABLE settings(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        db.execSQL(
            """CREATE TABLE queue(
                pos INTEGER PRIMARY KEY,
                video_id INTEGER NOT NULL
            )"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v0.1: no migrations yet.
    }

    private fun rowToItem(c: android.database.Cursor): VideoItem = VideoItem(
        id = c.getLong(0),
        videoKey = c.getString(1),
        title = c.getString(2),
        channel = if (c.isNull(3)) null else c.getString(3),
        sourceKind = c.getString(4),
        locator = c.getString(5),
        fileName = if (c.isNull(6)) null else c.getString(6),
        durationMs = c.getLong(7),
        addedAt = c.getLong(8),
        publishedAt = if (c.isNull(9)) null else c.getLong(9),
        status = c.getString(10),
        positionMs = c.getLong(11),
        playCount = c.getInt(12),
        lastPlayedAt = if (c.isNull(13)) null else c.getLong(13),
        completedAt = if (c.isNull(14)) null else c.getLong(14),
        deletedAt = if (c.isNull(15)) null else c.getLong(15)
    )

    private val cols = arrayOf(
        "id", "video_key", "title", "channel", "source_kind", "locator", "file_name",
        "duration_ms", "added_at", "published_at", "status", "position_ms", "play_count",
        "last_played_at", "completed_at", "deleted_at"
    )

    /** Insert if the video_key is new; returns the row id (existing or new). Re-registering a
     *  tombstoned item revives it (deleted_at cleared). */
    fun register(title: String, sourceKind: String, locator: String, fileName: String?): Long {
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("video_key", locator)
            put("title", title)
            put("source_kind", sourceKind)
            put("locator", locator)
            put("file_name", fileName)
            put("added_at", now)
        }
        val id = writableDatabase.insertWithOnConflict(
            "videos", null, values, SQLiteDatabase.CONFLICT_IGNORE
        )
        if (id == -1L) {
            // Already present: revive if tombstoned and return existing id.
            writableDatabase.execSQL(
                "UPDATE videos SET deleted_at=NULL WHERE video_key=?", arrayOf(locator)
            )
            return getByKey(locator)?.id ?: -1L
        }
        return id
    }

    fun getByKey(key: String): VideoItem? {
        readableDatabase.query("videos", cols, "video_key=?", arrayOf(key), null, null, null)
            .use { c -> return if (c.moveToFirst()) rowToItem(c) else null }
    }

    fun getById(id: Long): VideoItem? {
        readableDatabase.query("videos", cols, "id=?", arrayOf(id.toString()), null, null, null)
            .use { c -> return if (c.moveToFirst()) rowToItem(c) else null }
    }

    fun listVideos(filter: String): List<VideoItem> {
        val where = StringBuilder("deleted_at IS NULL")
        when (filter) {
            "unplayed" -> where.append(" AND status='unplayed'")
            "playing" -> where.append(" AND status='playing'")
            "played" -> where.append(" AND status='played'")
        }
        val out = mutableListOf<VideoItem>()
        readableDatabase.query(
            "videos", cols, where.toString(), null, null, null, "added_at DESC"
        ).use { c -> while (c.moveToNext()) out.add(rowToItem(c)) }
        return out
    }

    /** Count of non-deleted videos, optionally filtered by status (diagnostics summary). */
    fun countVisible(status: String?): Int {
        val where = if (status == null) "deleted_at IS NULL"
        else "deleted_at IS NULL AND status=?"
        val args = if (status == null) null else arrayOf(status)
        readableDatabase.query("videos", arrayOf("COUNT(*)"), where, args, null, null, null)
            .use { c -> return if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    fun setDuration(id: Long, durationMs: Long) {
        if (durationMs <= 0) return
        writableDatabase.execSQL(
            "UPDATE videos SET duration_ms=? WHERE id=? AND duration_ms=0",
            arrayOf(durationMs, id)
        )
    }

    /** Periodic/pause persistence of playback progress. Marks played past 90%. */
    fun saveProgress(id: Long, positionMs: Long) {
        val item = getById(id) ?: return
        val values = ContentValues().apply {
            put("position_ms", positionMs)
            put("last_played_at", System.currentTimeMillis())
            if (item.status == "unplayed") put("status", "playing")
        }
        writableDatabase.update("videos", values, "id=?", arrayOf(id.toString()))
        if (item.durationMs > 0 && positionMs >= item.durationMs * 9 / 10) {
            markPlayed(id)
        }
    }

    fun notePlayStarted(id: Long) {
        writableDatabase.execSQL(
            "UPDATE videos SET play_count=play_count+1, last_played_at=? WHERE id=?",
            arrayOf(System.currentTimeMillis(), id)
        )
    }

    fun markPlayed(id: Long) {
        val values = ContentValues().apply {
            put("status", "played")
            put("completed_at", System.currentTimeMillis())
            put("position_ms", 0L)
        }
        writableDatabase.update("videos", values, "id=?", arrayOf(id.toString()))
    }

    fun softDelete(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        val now = System.currentTimeMillis()
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (id in ids) {
                val values = ContentValues().apply { put("deleted_at", now) }
                db.update("videos", values, "id=?", arrayOf(id.toString()))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getSetting(key: String): String? {
        readableDatabase.query("settings", arrayOf("value"), "key=?", arrayOf(key), null, null, null)
            .use { c -> return if (c.moveToFirst()) c.getString(0) else null }
    }

    fun setSetting(key: String, value: String) {
        val values = ContentValues().apply {
            put("key", key)
            put("value", value)
        }
        writableDatabase.insertWithOnConflict(
            "settings", null, values, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun saveQueue(ids: List<Long>, currentIndex: Int) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("queue", null, null)
            ids.forEachIndexed { i, vid ->
                val values = ContentValues().apply {
                    put("pos", i)
                    put("video_id", vid)
                }
                db.insert("queue", null, values)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        setSetting("queue_ids", JSONArray(ids).toString())
        setSetting("current_index", currentIndex.toString())
    }

    fun loadQueueIds(): List<Long> {
        val raw = getSetting("queue_ids") ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            List(arr.length()) { arr.getLong(it) }.filter { getById(it)?.deletedAt == null }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun loadCurrentIndex(): Int = getSetting("current_index")?.toIntOrNull() ?: 0
}

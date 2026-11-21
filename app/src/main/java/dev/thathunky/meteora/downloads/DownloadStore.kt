package dev.thathunky.meteora.downloads

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Where the index lives. SQL stays behind this, so the logic above it is tested with [MemoryDownloadStore]. */
interface DownloadStore {
    fun songs(): List<DownloadEntry>
    fun putSong(e: DownloadEntry)
    fun deleteSong(videoId: String)
    fun collections(): List<DownloadCollection>
    fun putCollection(c: DownloadCollection)
    fun deleteCollection(id: String)
}

class MemoryDownloadStore : DownloadStore {
    private val songs = LinkedHashMap<String, DownloadEntry>()
    private val collections = LinkedHashMap<String, DownloadCollection>()
    @Synchronized override fun songs() = songs.values.toList()
    @Synchronized override fun putSong(e: DownloadEntry) { songs[e.videoId] = e }
    @Synchronized override fun deleteSong(videoId: String) { songs.remove(videoId) }
    @Synchronized override fun collections() = collections.values.toList()
    @Synchronized override fun putCollection(c: DownloadCollection) { collections[c.id] = c }
    @Synchronized override fun deleteCollection(id: String) { collections.remove(id) }
}

/** `downloads.db`: a songs table and a collections table. Lists are stored as text joined by [SEP]. */
class SqliteDownloadStore(context: Context) : SQLiteOpenHelper(context, "downloads.db", null, VERSION), DownloadStore {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE songs (video_id TEXT PRIMARY KEY, title TEXT NOT NULL, artist TEXT NOT NULL, album TEXT,
               album_artist TEXT, track INTEGER, year TEXT, artwork TEXT, duration REAL NOT NULL, state TEXT NOT NULL,
               uri TEXT, itag INTEGER NOT NULL, bytes INTEGER NOT NULL, single INTEGER NOT NULL, smart INTEGER NOT NULL,
               collections TEXT NOT NULL, added INTEGER NOT NULL)""",
        )
        db.execSQL(
            """CREATE TABLE collections (id TEXT PRIMARY KEY, title TEXT NOT NULL, is_album INTEGER NOT NULL, thumbnail TEXT,
               browse_id TEXT, video_ids TEXT NOT NULL, added INTEGER NOT NULL)""",
        )
        ListenTables.create(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) ListenTables.create(db)
    }

    override fun songs(): List<DownloadEntry> = readableDatabase.query("songs", null, null, null, null, null, "added").use { c ->
        buildList { while (c.moveToNext()) add(c.song()) }
    }

    override fun putSong(e: DownloadEntry) {
        writableDatabase.insertWithOnConflict("songs", null, ContentValues().apply {
            put("video_id", e.videoId); put("title", e.title); put("artist", e.artist); put("album", e.album)
            put("album_artist", e.albumArtist); put("track", e.trackNumber); put("year", e.year); put("artwork", e.artworkUrl)
            put("duration", e.durationSec); put("state", e.state.name); put("uri", e.uri); put("itag", e.itag); put("bytes", e.bytes)
            put("single", if (e.single) 1 else 0); put("smart", if (e.smart) 1 else 0)
            put("collections", e.collections.joinToString(SEP)); put("added", e.addedAtMs)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun deleteSong(videoId: String) {
        writableDatabase.delete("songs", "video_id = ?", arrayOf(videoId))
    }

    override fun collections(): List<DownloadCollection> = readableDatabase.query("collections", null, null, null, null, null, "added").use { c ->
        buildList {
            while (c.moveToNext()) add(
                DownloadCollection(
                    id = c.str("id")!!, title = c.str("title")!!, isAlbum = c.int("is_album") == 1, thumbnail = c.str("thumbnail"),
                    browseId = c.str("browse_id"), videoIds = c.str("video_ids")!!.split(SEP).filter { it.isNotEmpty() }, addedAtMs = c.long("added"),
                ),
            )
        }
    }

    override fun putCollection(c: DownloadCollection) {
        writableDatabase.insertWithOnConflict("collections", null, ContentValues().apply {
            put("id", c.id); put("title", c.title); put("is_album", if (c.isAlbum) 1 else 0); put("thumbnail", c.thumbnail)
            put("browse_id", c.browseId); put("video_ids", c.videoIds.joinToString(SEP)); put("added", c.addedAtMs)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun deleteCollection(id: String) {
        writableDatabase.delete("collections", "id = ?", arrayOf(id))
    }

    private fun Cursor.song() = DownloadEntry(
        videoId = str("video_id")!!, title = str("title")!!, artist = str("artist")!!, album = str("album"), albumArtist = str("album_artist"),
        trackNumber = if (isNull(getColumnIndexOrThrow("track"))) null else int("track"), year = str("year"), artworkUrl = str("artwork"),
        durationSec = getDouble(getColumnIndexOrThrow("duration")),
        state = DownloadState.entries.firstOrNull { it.name == str("state") } ?: DownloadState.Failed,
        uri = str("uri"), itag = int("itag"), bytes = long("bytes"), single = int("single") == 1, smart = int("smart") == 1,
        collections = str("collections")!!.split(SEP).filter { it.isNotEmpty() }.toSet(), addedAtMs = long("added"),
    )

    private companion object {
        const val VERSION = 2
        /** Video and playlist ids never contain a comma. */
        const val SEP = ","
    }
}

internal fun Cursor.str(col: String): String? = getString(getColumnIndexOrThrow(col))
internal fun Cursor.int(col: String): Int = getInt(getColumnIndexOrThrow(col))
internal fun Cursor.long(col: String): Long = getLong(getColumnIndexOrThrow(col))

/** The listening log's table (D5), in the same database. */
internal object ListenTables {
    fun create(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS listens (video_id TEXT PRIMARY KEY, plays INTEGER NOT NULL, skips INTEGER NOT NULL,
               last_played INTEGER NOT NULL, title TEXT NOT NULL, artist TEXT NOT NULL, album TEXT, album_id TEXT,
               artwork TEXT, duration REAL NOT NULL)""",
        )
    }
}

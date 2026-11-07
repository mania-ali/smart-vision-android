package com.example.myapplication.memory

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import android.view.View
import com.example.myapplication.detection.Detection
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One remembered object: a simplified version of the ObjectAnchor table from the design chapter.
 */
data class ObjectRecord(
    val id: Long,
    val label: String,
    val zone: String,        // "left" / "centre" / "right"
    val confidence: Float,
    val lastSeen: Long,      // System.currentTimeMillis() of the last sighting
    val timesSeen: Int
) {
    override fun toString(): String {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(lastSeen))
        return "ObjectRecord(id=$id, label=$label, zone=$zone, " +
            "confidence=${"%.2f".format(confidence)}, lastSeen=$time, timesSeen=$timesSeen)"
    }
}

/**
 * Basic spatial memory: remembers each object the app has seen, where on screen (zone) and when.
 * Stored in SQLite, so it survives the app being closed.
 *
 * Used by MainActivity with three calls:
 *   memory = SpatialMemory(this)      // in onCreate()
 *   memory.save(results)              // in processFrame(), after detector.detect()
 *   memory.close()                    // in onDestroy()
 */
class SpatialMemory(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val TAG = "SVA_DB"
        private const val DB_NAME = "sva_memory.db"
        private const val DB_VERSION = 1

        const val TABLE = "object_anchor"
        private const val COL_ID = "id"
        private const val COL_LABEL = "label"
        private const val COL_ZONE = "zone"
        private const val COL_CONFIDENCE = "confidence"
        private const val COL_LAST_SEEN = "last_seen"
        private const val COL_TIMES_SEEN = "times_seen"

        /** Write to the database at most once per second. */
        private const val SAVE_INTERVAL_MS = 1000L

        /** Same rule as AudioFeedback: box centre below 0.33 is left, above 0.66 is right. */
        fun zoneFor(cx: Float): String = when {
            cx < 0.33f -> "left"
            cx > 0.66f -> "right"
            else -> "centre"
        }
    }

    private var lastSaveTime = 0L
    private var closed = false

    /** Called from MainActivity.onDestroy(). After this, late frames from the camera thread are ignored. */
    @Synchronized
    override fun close() {
        closed = true
        super.close()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE (
                $COL_ID          INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_LABEL       TEXT    NOT NULL UNIQUE,
                $COL_ZONE        TEXT    NOT NULL,
                $COL_CONFIDENCE  REAL    NOT NULL,
                $COL_LAST_SEEN   INTEGER NOT NULL,
                $COL_TIMES_SEEN  INTEGER NOT NULL DEFAULT 1
            )
            """.trimIndent()
        )
        Log.d(TAG, "Created table $TABLE")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // FYP-1: no data worth migrating yet, so just rebuild the table.
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        onCreate(db)
    }

    /**
     * Saves one frame's detections. Called on every frame, but only writes once per second.
     * Existing label -> update zone, confidence, last_seen and add 1 to times_seen.
     * New label      -> insert a new row.
     */
    @Synchronized
    fun save(results: List<Detection>) {
        if (closed || results.isEmpty()) return

        val now = System.currentTimeMillis()
        if (now - lastSaveTime < SAVE_INTERVAL_MS) return
        lastSaveTime = now

        // label is UNIQUE, so if one frame has two of the same object (e.g. two people),
        // keep only the most confident one.
        val bestPerLabel = HashMap<String, Detection>()
        for (d in results) {
            val current = bestPerLabel[d.label]
            if (current == null || d.score > current.score) bestPerLabel[d.label] = d
        }

        val db = writableDatabase
        db.beginTransaction()
        val update = db.compileStatement(
            "UPDATE $TABLE SET $COL_ZONE = ?, $COL_CONFIDENCE = ?, $COL_LAST_SEEN = ?, " +
                "$COL_TIMES_SEEN = $COL_TIMES_SEEN + 1 WHERE $COL_LABEL = ?"
        )
        try {
            for ((label, d) in bestPerLabel) {
                val cx = (d.box.left + d.box.right) / 2f   // box values are 0..1
                val zone = zoneFor(cx)

                update.clearBindings()
                update.bindString(1, zone)
                update.bindDouble(2, d.score.toDouble())
                update.bindLong(3, now)
                update.bindString(4, label)
                val rowsChanged = update.executeUpdateDelete()

                if (rowsChanged == 0) {
                    val values = ContentValues().apply {
                        put(COL_LABEL, label)
                        put(COL_ZONE, zone)
                        put(COL_CONFIDENCE, d.score)
                        put(COL_LAST_SEEN, now)
                        put(COL_TIMES_SEEN, 1)
                    }
                    db.insert(TABLE, null, values)
                    Log.d(TAG, "INSERT $label zone=$zone conf=${"%.2f".format(d.score)}")
                } else {
                    Log.d(TAG, "UPDATE $label zone=$zone conf=${"%.2f".format(d.score)}")
                }
            }
            db.setTransactionSuccessful()
        } catch (e: Exception) {
            Log.e(TAG, "save() failed", e)
        } finally {
            db.endTransaction()
            update.close()
        }
    }

    /** Returns the remembered row for this label, or null if it has never been seen. */
    @Synchronized
    fun lastSeen(label: String): ObjectRecord? {
        if (closed) return null
        readableDatabase.query(
            TABLE, null, "$COL_LABEL = ?", arrayOf(label), null, null, null
        ).use { c ->
            if (!c.moveToFirst()) return null
            return ObjectRecord(
                id = c.getLong(c.getColumnIndexOrThrow(COL_ID)),
                label = c.getString(c.getColumnIndexOrThrow(COL_LABEL)),
                zone = c.getString(c.getColumnIndexOrThrow(COL_ZONE)),
                confidence = c.getFloat(c.getColumnIndexOrThrow(COL_CONFIDENCE)),
                lastSeen = c.getLong(c.getColumnIndexOrThrow(COL_LAST_SEEN)),
                timesSeen = c.getInt(c.getColumnIndexOrThrow(COL_TIMES_SEEN))
            )
        }
    }

    /**
     * TEMPORARY debug hook for TC-M04: long-press the given view to log lastSeen(label).
     * MainActivity only needs one line: memory.attachDebugLongPress(someView)
     * Remove before the final build.
     */
    fun attachDebugLongPress(view: View, label: String = "person") {
        view.isLongClickable = true
        view.setOnLongClickListener {
            val row = lastSeen(label)
            Log.d(TAG, "lastSeen(\"$label\") -> ${row ?: "null (never seen)"}")
            true
        }
    }
}
package org.fossify.messages.helpers

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.messages.extensions.config
import org.fossify.messages.models.Events
import org.greenrobot.eventbus.EventBus

/**
 * sms-man: the notes ("tags") that Samsung's Phone app saves for a number in the call log, for example
 * "م بارنامه - خانم". They are in Samsung's own call log table (column `sec_memo`), not in Android's CallLog.
 * Other phones have no such table; then there are simply no notes.
 *
 * All notes are read with one query into memory, so [get] is fast enough for list scrolling.
 */
object CallLogNotes {
    private const val TAG = "CallLogNotes"
    private val SAMSUNG_LOGS_URI = "content://logs/historys".toUri()
    private const val COLUMN_NUMBER = "number"
    private const val COLUMN_NOTE = "sec_memo"
    private const val MIN_RELOAD_INTERVAL_MS = 60_000L
    private const val KEY_DIGITS = 10
    private const val MIN_KEY_DIGITS = 7

    @Volatile
    private var notes: Map<String, String> = emptyMap()

    @Volatile
    private var lastLoad = 0L

    fun hasPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED

    /** The note of [number], or null. Fast (memory only). */
    fun get(context: Context, number: String): String? {
        if (notes.isEmpty() || !context.config.callLogNotesEnabled) return null
        return key(number)?.let { notes[it] }
    }

    /** Reads all notes again. Blocking; call it from a background thread. @return true if the notes changed */
    fun reload(context: Context): Boolean {
        lastLoad = System.currentTimeMillis()
        val map = HashMap<String, String>()
        if (context.config.callLogNotesEnabled && hasPermission(context)) {
            try {
                context.contentResolver.query(
                    SAMSUNG_LOGS_URI,
                    arrayOf(COLUMN_NUMBER, COLUMN_NOTE),
                    "$COLUMN_NOTE IS NOT NULL AND $COLUMN_NOTE != ''",
                    null,
                    "date ASC" // the newest note of a number wins
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val key = key(cursor.getString(0) ?: continue) ?: continue
                        val note = cursor.getString(1)?.trim()
                        if (!note.isNullOrEmpty()) map[key] = note
                    }
                }
                Log.i(TAG, "loaded ${map.size} call log notes")
            } catch (e: Exception) {
                Log.w(TAG, "cannot read the call log notes: $e")
            }
        }

        val changed = map != notes
        notes = map
        return changed
    }

    /** Reload at most once a minute, in the background. Lists are redrawn when something changed. */
    fun reloadInBackground(context: Context, force: Boolean = false) {
        if (!force && System.currentTimeMillis() - lastLoad < MIN_RELOAD_INTERVAL_MS) return
        lastLoad = System.currentTimeMillis()
        val appContext = context.applicationContext
        ensureBackgroundThread {
            if (reload(appContext)) {
                EventBus.getDefault().post(Events.AvatarsChanged())
            }
        }
    }

    /** For background code (SMS receiver): load once if not loaded yet. */
    fun ensureLoaded(context: Context) {
        if (lastLoad == 0L) reload(context)
    }

    private fun key(number: String): String? {
        val digits = number.filter { it.isDigit() }
        return if (digits.length >= MIN_KEY_DIGITS) digits.takeLast(KEY_DIGITS) else null
    }
}

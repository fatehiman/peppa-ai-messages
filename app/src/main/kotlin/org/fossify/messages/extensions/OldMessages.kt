package org.fossify.messages.extensions

import android.content.Context
import android.net.Uri
import android.provider.BaseColumns
import android.provider.Telephony.Mms
import android.provider.Telephony.Sms
import android.provider.Telephony.Threads
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.messages.helpers.refreshConversations

// sms-man: "Delete old messages" (Settings). Locked messages and scheduled messages are kept.

private const val DAY_MS = 24 * 60 * 60 * 1000L

private fun cutoffMillis(days: Int) = System.currentTimeMillis() - days * DAY_MS

/** How many messages are older than [days] days: SMS + MMS (not locked) + the spam folder. */
fun Context.countOldMessages(days: Int): Int {
    val cutoff = cutoffMillis(days)
    return countRows(Sms.CONTENT_URI, "${Sms.DATE} < ? AND ${Sms.LOCKED} = 0", cutoff) +
        countRows(Mms.CONTENT_URI, "${Mms.DATE} < ? AND ${Mms.LOCKED} = 0", cutoff / 1000) +
        spamMessagesDB.countOlderThan(cutoff)
}

/** Delete messages older than [days] days now. Slow with many messages: call it from a background thread. */
fun Context.deleteOldMessages(days: Int): Int {
    val cutoff = cutoffMillis(days)
    config.lastAutoDeleteOld = System.currentTimeMillis()
    var count = deleteRows(Sms.CONTENT_URI, "${Sms.DATE} < ? AND ${Sms.LOCKED} = 0", cutoff)
    count += deleteRows(Mms.CONTENT_URI, "${Mms.DATE} < ? AND ${Mms.LOCKED} = 0", cutoff / 1000) // MMS date is in seconds
    count += spamMessagesDB.deleteOlderThan(cutoff)

    // Let the provider remove the threads that are empty now (same trick as updateLastConversationMessage)
    try {
        contentResolver.delete(Threads.CONTENT_URI, "1 = 0", null)
    } catch (_: Exception) {
    }
    messagesDB.deleteOlderThan((cutoff / 1000).toInt())
    messagesDB.deleteOrphanRecycleBinEntries()
    return count
}

/** The daily automatic run, without asking. Called when the app starts and after each received SMS. */
fun Context.checkAndDeleteOldMessages() {
    val now = System.currentTimeMillis()
    if (!config.autoDeleteOldEnabled || config.lastAutoDeleteOld > now - DAY_MS) return
    config.lastAutoDeleteOld = now // set first, so two callers at the same time do not both run
    ensureBackgroundThread {
        try {
            if (deleteOldMessages(config.autoDeleteOldDays) > 0) refreshConversations()
        } catch (_: Exception) {
        }
    }
}

private fun Context.countRows(uri: Uri, selection: String, value: Long): Int = try {
    contentResolver.query(uri, arrayOf(BaseColumns._ID), selection, arrayOf(value.toString()), null)
        ?.use { it.count } ?: 0
} catch (_: Exception) {
    0
}

private fun Context.deleteRows(uri: Uri, selection: String, value: Long): Int = try {
    contentResolver.delete(uri, selection, arrayOf(value.toString()))
} catch (_: Exception) {
    0
}

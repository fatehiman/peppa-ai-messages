package org.fossify.messages.extensions

import android.content.Context
import android.provider.Telephony.Sms
import org.fossify.messages.helpers.SpamFilter
import org.fossify.messages.models.SpamMessage

/**
 * sms-man: "Not spam". Moves the message into the system SMS database (normal inbox, marked read)
 * and adds the sender to the local allow list, so it is never checked again.
 */
fun Context.restoreSpamMessage(message: SpamMessage) {
    val threadId = getThreadId(message.address)
    val newId = insertNewSMS(
        address = message.address,
        subject = "",
        body = message.body,
        date = message.date,
        read = 1,
        threadId = threadId,
        type = Sms.MESSAGE_TYPE_INBOX,
        subscriptionId = message.subscriptionId
    )
    if (newId == 0L) throw IllegalStateException("could not write the SMS (is this the default SMS app?)")

    getConversations(threadId).firstOrNull()?.let { conv ->
        runCatching { insertOrUpdateConversation(conv) }
    }
    spamMessagesDB.delete(message.id)
    config.spamAllowedSenders += SpamFilter.normalizeSender(message.address)
}

/**
 * sms-man: "Report as spam" for a whole conversation. Its received SMS are copied into the spam
 * folder, then the conversation is deleted from the system database (sent messages too).
 * @return the sender addresses of the moved messages
 */
fun Context.moveConversationToSpam(threadId: Long): Set<String> {
    val senders = HashSet<String>()
    val projection = arrayOf(Sms.ADDRESS, Sms.BODY, Sms.DATE, Sms.SUBSCRIPTION_ID)
    val selection = "${Sms.THREAD_ID} = ? AND ${Sms.TYPE} = ?"
    val selectionArgs = arrayOf(threadId.toString(), Sms.MESSAGE_TYPE_INBOX.toString())
    contentResolver.query(Sms.CONTENT_URI, projection, selection, selectionArgs, null)?.use { cursor ->
        while (cursor.moveToNext()) {
            val address = cursor.getString(0).orEmpty()
            spamMessagesDB.insert(
                SpamMessage(
                    address = address,
                    body = cursor.getString(1).orEmpty(),
                    date = cursor.getLong(2),
                    subscriptionId = cursor.getInt(3),
                    reason = SpamFilter.REASON_REPORT,
                    percent = null
                )
            )
            if (address.isNotEmpty()) senders.add(address)
        }
    }

    deleteConversation(threadId)
    config.spamAllowedSenders -= senders.map { SpamFilter.normalizeSender(it) }.toSet()
    return senders
}

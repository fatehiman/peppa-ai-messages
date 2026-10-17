package org.fossify.messages.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import org.fossify.commons.extensions.getMyContactsCursor
import org.fossify.commons.helpers.SimpleContactsHelper
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.models.PhoneNumber
import org.fossify.commons.models.SimpleContact
import org.fossify.messages.extensions.checkAndDeleteOldMessages
import org.fossify.messages.extensions.getConversations
import org.fossify.messages.extensions.getNameFromAddress
import org.fossify.messages.extensions.getNotificationBitmap
import org.fossify.messages.extensions.getThreadId
import org.fossify.messages.extensions.insertNewSMS
import org.fossify.messages.extensions.insertOrUpdateConversation
import org.fossify.messages.extensions.messagesDB
import org.fossify.messages.extensions.shouldUnarchive
import org.fossify.messages.extensions.showReceivedMessageNotification
import org.fossify.messages.extensions.spamMessagesDB
import org.fossify.messages.extensions.updateConversationArchivedStatus
import org.fossify.messages.helpers.SpamFilter
import org.fossify.messages.helpers.refreshConversations
import org.fossify.messages.helpers.refreshMessages
import org.fossify.messages.models.Message
import org.fossify.messages.models.SpamMessage

class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val appContext = context.applicationContext

        ensureBackgroundThread {
            try {
                val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent)
                if (parts.isEmpty()) return@ensureBackgroundThread

                // this is how it has always worked, but need to revisit this.
                val address = parts.last().originatingAddress.orEmpty()
                if (address.isBlank()) return@ensureBackgroundThread
                val subject = parts.last().pseudoSubject.orEmpty()
                val status = parts.last().status
                val body = buildString { parts.forEach { append(it.messageBody.orEmpty()) } }

                val date = System.currentTimeMillis()
                val dateSent = parts.first().timestampMillis // sms-man: real send time, shown in Properties
                val subscriptionId = intent.getIntExtra("subscription", -1)

                // sms-man: blocked numbers / keywords and spam go only to the app's own spam folder (no system DB
                // row, no thread, no notification), instead of being dropped. See SpamFilter.decide for the rules.
                // This runs before getThreadId(), which would create an empty thread in the system DB.
                val decision = SpamFilter.decide(appContext, address, body)
                if (decision is SpamFilter.Decision.Spam &&
                    saveSpam(appContext, address, body, date, subscriptionId, decision)
                ) {
                    return@ensureBackgroundThread
                }

                val threadId = appContext.getThreadId(address)

                handleMessageSync(
                    context = appContext,
                    address = address,
                    subject = subject,
                    body = body,
                    date = date,
                    dateSent = dateSent,
                    threadId = threadId,
                    subscriptionId = subscriptionId,
                    status = status
                )
                appContext.checkAndDeleteOldMessages() // sms-man: daily "delete old messages"
            } finally {
                pending.finish()
            }
        }
    }

    /** Save the SMS in the spam folder. false = it could not be saved (then it goes to the inbox). */
    private fun saveSpam(
        context: Context,
        address: String,
        body: String,
        date: Long,
        subscriptionId: Int,
        decision: SpamFilter.Decision.Spam
    ): Boolean {
        return try {
            context.spamMessagesDB.insert(
                SpamMessage(
                    address = address,
                    body = body,
                    date = date,
                    subscriptionId = subscriptionId,
                    reason = decision.reason,
                    percent = decision.percent
                )
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun handleMessageSync(
        context: Context,
        address: String,
        subject: String,
        body: String,
        date: Long,
        dateSent: Long,
        read: Int = 0,
        threadId: Long,
        type: Int = Telephony.Sms.MESSAGE_TYPE_INBOX,
        subscriptionId: Int,
        status: Int
    ) {
        val photoUri = SimpleContactsHelper(context).getPhotoUriFromPhoneNumber(address)
        val bitmap = context.getNotificationBitmap(photoUri)

        val newMessageId = context.insertNewSMS(
            address = address,
            subject = subject,
            body = body,
            date = date,
            read = read,
            threadId = threadId,
            type = type,
            subscriptionId = subscriptionId,
            dateSent = dateSent
        )

        context.getConversations(threadId).firstOrNull()?.let { conv ->
            runCatching { context.insertOrUpdateConversation(conv) }
        }

        val senderName = context.getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true).use {
            context.getNameFromAddress(address, it)
        }

        val participant = SimpleContact(
            rawId = 0,
            contactId = 0,
            name = senderName,
            photoUri = photoUri,
            phoneNumbers = arrayListOf(PhoneNumber(value = address, type = 0, label = "", normalizedNumber = address)),
            birthdays = ArrayList(),
            anniversaries = ArrayList()
        )

        val message = Message(
            id = newMessageId,
            body = body,
            type = type,
            status = status,
            participants = arrayListOf(participant),
            date = (date / 1000).toInt(),
            read = false,
            threadId = threadId,
            isMMS = false,
            attachment = null,
            senderPhoneNumber = address,
            senderName = senderName,
            senderPhotoUri = photoUri,
            subscriptionId = subscriptionId
        )

        context.messagesDB.insertOrUpdate(message)

        if (context.shouldUnarchive()) {
            context.updateConversationArchivedStatus(threadId, false)
        }

        refreshMessages()
        refreshConversations()
        context.showReceivedMessageNotification(
            messageId = newMessageId,
            isMms = false,
            address = address,
            senderName = senderName,
            body = body,
            threadId = threadId,
            bitmap = bitmap
        )
    }
}

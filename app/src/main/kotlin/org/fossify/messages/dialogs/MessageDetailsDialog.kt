package org.fossify.messages.dialogs

import android.annotation.SuppressLint
import android.content.ContentUris
import android.provider.Telephony.Mms
import android.provider.Telephony.Sms
import android.telephony.SubscriptionInfo
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.dialogs.BasePropertiesDialog
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.getTimeFormatWithSeconds
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.messages.R
import org.fossify.messages.extensions.config
import org.fossify.messages.extensions.subscriptionManagerCompat
import org.fossify.messages.models.Message
import org.joda.time.DateTime

class MessageDetailsDialog(val activity: BaseSimpleActivity, val message: Message) : BasePropertiesDialog(activity) {
    init {
        @SuppressLint("MissingPermission")
        val availableSIMs = activity.subscriptionManagerCompat().activeSubscriptionInfoList.orEmpty()

        addProperty(message.getSenderOrReceiverLabel(), message.getSenderOrReceiverPhoneNumbers())
        if (availableSIMs.count() > 1) {
            addProperty(R.string.message_details_sim, message.getSIM(availableSIMs))
        }
        // sms-man: received: "Sent at" (sender's time) + "Received at". Sent: "Sent at" + "Delivered at".
        val (dateSent, status) = message.getDateSentAndStatus()
        if (message.isReceivedMessage()) {
            if (dateSent > 0L) addProperty(R.string.message_details_sent_at, formatDate(dateSent))
            addProperty(R.string.message_details_received_at, formatDate(message.millis()))
        } else {
            addProperty(R.string.message_details_sent_at, formatDate(message.millis()))
            if (!message.isMMS && status == Sms.STATUS_COMPLETE && dateSent > 0L) {
                addProperty(R.string.message_details_delivered_at, formatDate(dateSent))
            }
        }

        activity.getAlertDialogBuilder()
            .setPositiveButton(org.fossify.commons.R.string.ok) { _, _ -> }
            .apply {
                activity.setupDialogStuff(mDialogView.root, this, R.string.message_details)
            }
    }

    private fun Message.getSenderOrReceiverLabel(): Int {
        return if (isReceivedMessage()) {
            R.string.message_details_sender
        } else {
            R.string.message_details_receiver
        }
    }

    private fun Message.getSenderOrReceiverPhoneNumbers(): String {
        return if (isReceivedMessage()) {
            formatContactInfo(senderName, senderPhoneNumber)
        } else {
            participants.joinToString(", ") {
                formatContactInfo(it.name, it.phoneNumbers.first().value)
            }
        }
    }

    private fun formatContactInfo(name: String, phoneNumber: String): String {
        return if (name != phoneNumber) {
            "$name ($phoneNumber)"
        } else {
            phoneNumber
        }
    }

    private fun Message.getSIM(availableSIMs: List<SubscriptionInfo>): String {
        return availableSIMs.firstOrNull { it.subscriptionId == subscriptionId }?.displayName?.toString()
            ?: activity.getString(org.fossify.commons.R.string.unknown)
    }

    /**
     * sms-man: date_sent and status from the phone's SMS database (not in the app's cache). For a received SMS
     * date_sent is the time the sender sent it. For a sent SMS it is the delivery time (SmsStatusDeliveredReceiver).
     */
    private fun Message.getDateSentAndStatus(): Pair<Long, Int> {
        if (isScheduled) return Pair(0L, Sms.STATUS_NONE)
        return try {
            if (isMMS) {
                val uri = ContentUris.withAppendedId(Mms.CONTENT_URI, id)
                activity.contentResolver.query(uri, arrayOf(Mms.DATE_SENT), null, null, null)?.use {
                    if (it.moveToFirst()) Pair(it.getLong(0) * 1000L, Sms.STATUS_NONE) else null
                }
            } else {
                val uri = ContentUris.withAppendedId(Sms.CONTENT_URI, id)
                activity.contentResolver.query(uri, arrayOf(Sms.DATE_SENT, Sms.STATUS), null, null, null)?.use {
                    if (it.moveToFirst()) Pair(it.getLong(0), it.getInt(1)) else null
                }
            } ?: Pair(0L, Sms.STATUS_NONE)
        } catch (_: Exception) {
            Pair(0L, Sms.STATUS_NONE)
        }
    }

    private fun formatDate(millis: Long): String {
        return DateTime(millis).toString("${activity.config.dateFormat} ${activity.getTimeFormatWithSeconds()}")
    }
}

package org.fossify.messages.helpers

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager.IMPORTANCE_HIGH
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.provider.Telephony
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.graphics.drawable.IconCompat
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.notificationManager
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.helpers.isRPlus
import org.fossify.messages.R
import org.fossify.messages.activities.BubbleThreadActivity
import org.fossify.messages.activities.ThreadActivity
import org.fossify.messages.extensions.config
import org.fossify.messages.extensions.shortcutHelper
import org.fossify.messages.messaging.isShortCodeWithLetters
import org.fossify.messages.receivers.DeleteSmsReceiver
import org.fossify.messages.receivers.DirectReplyReceiver
import org.fossify.messages.receivers.MarkAsReadReceiver

private const val BUBBLE_HEIGHT_DP = 600

class NotificationHelper(private val context: Context) {

    private val notificationManager = context.notificationManager
    private val soundUri get() = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
    private val user = Person.Builder()
        .setName(context.getString(R.string.me))
        .build()

    @SuppressLint("NewApi")
    fun showMessageNotification(
        messageId: Long,
        isMms: Boolean,
        address: String,
        body: String,
        threadId: Long,
        bitmap: Bitmap?,
        sender: String?,
        alertOnlyOnce: Boolean = false
    ) {
        val hasCustomNotifications =
            context.config.customNotifications.contains(threadId.toString())
        val notificationChannelId =
            if (hasCustomNotifications) threadId.toString() else NOTIFICATION_CHANNEL_ID
        if (!hasCustomNotifications) {
            createChannel(notificationChannelId, context.getString(R.string.channel_received_sms))
        }

        val notificationId = threadId.hashCode()
        val contentIntent = Intent(context, ThreadActivity::class.java).apply {
            putExtra(THREAD_ID, threadId)
        }
        val contentPendingIntent =
            PendingIntent.getActivity(
                context,
                notificationId,
                contentIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )

        val markAsReadIntent = Intent(context, MarkAsReadReceiver::class.java).apply {
            action = MARK_AS_READ
            putExtra(THREAD_ID, threadId)
        }
        val markAsReadPendingIntent =
            PendingIntent.getBroadcast(
                context,
                notificationId,
                markAsReadIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        val deleteSmsIntent = Intent(context, DeleteSmsReceiver::class.java).apply {
            putExtra(THREAD_ID, threadId)
            putExtra(MESSAGE_ID, messageId)
            putExtra(IS_MMS, isMms)
        }
        val deleteSmsPendingIntent =
            PendingIntent.getBroadcast(
                context,
                notificationId,
                deleteSmsIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        var replyAction: NotificationCompat.Action? = null
        val isNoReplySms = isShortCodeWithLetters(address)
        if (!isNoReplySms) {
            val replyLabel = context.getString(R.string.reply)
            val remoteInput = RemoteInput.Builder(REPLY)
                .setLabel(replyLabel)
                .build()

            val replyIntent = Intent(context, DirectReplyReceiver::class.java).apply {
                putExtra(THREAD_ID, threadId)
                putExtra(THREAD_NUMBER, address)
            }

            val replyPendingIntent =
                PendingIntent.getBroadcast(
                    context.applicationContext,
                    notificationId,
                    replyIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )
            replyAction = NotificationCompat.Action.Builder(
                R.drawable.ic_send_vector,
                replyLabel,
                replyPendingIntent
            )
                .addRemoteInput(remoteInput)
                .build()
        }

        val largeIcon = bitmap ?: if (sender != null) {
            Avatars.getLetterIcon(context, sender, address) // sms-man
        } else {
            null
        }
        val builder = NotificationCompat.Builder(context, notificationChannelId).apply {
            when (context.config.lockScreenVisibilitySetting) {
                LOCK_SCREEN_SENDER_MESSAGE -> {
                    setLargeIcon(largeIcon)
                    setStyle(getMessagesStyle(address, body, notificationId, sender))
                }

                LOCK_SCREEN_SENDER -> {
                    setContentTitle(sender)
                    setLargeIcon(largeIcon)
                    val summaryText = context.getString(R.string.new_message)
                    setStyle(
                        NotificationCompat.BigTextStyle().setSummaryText(summaryText).bigText(body)
                    )
                }
            }

            color = context.getProperPrimaryColor()
            setSmallIcon(R.drawable.ic_messenger)
            setContentIntent(contentPendingIntent)
            priority = NotificationCompat.PRIORITY_MAX
            setDefaults(Notification.DEFAULT_LIGHTS)
            setCategory(Notification.CATEGORY_MESSAGE)
            setAutoCancel(true)
            setOnlyAlertOnce(alertOnlyOnce)
            setSound(soundUri, AudioManager.STREAM_NOTIFICATION)
        }

        if (replyAction != null && context.config.lockScreenVisibilitySetting == LOCK_SCREEN_SENDER_MESSAGE) {
            builder.addAction(replyAction)
        }

        builder.addAction(
            org.fossify.commons.R.drawable.ic_check_vector,
            context.getString(R.string.mark_as_read),
            markAsReadPendingIntent
        )
            .setChannelId(notificationChannelId)
        if (isNoReplySms) {
            builder.addAction(
                org.fossify.commons.R.drawable.ic_delete_vector,
                context.getString(org.fossify.commons.R.string.delete),
                deleteSmsPendingIntent
            ).setChannelId(notificationChannelId)
        }

        // sms-man: floating chat bubble. Only when the message text may be shown (bubbles need MessagingStyle)
        if (
            isRPlus() && context.config.bubbleNotifications &&
            context.config.lockScreenVisibilitySetting == LOCK_SCREEN_SENDER_MESSAGE
        ) {
            builder.setBubbleMetadata(getBubbleMetadata(threadId, notificationId, largeIcon))
        }

        var shortcut = context.shortcutHelper.getShortcut(threadId)
        if (shortcut == null) {
            ensureBackgroundThread {
                shortcut = context.shortcutHelper.createOrUpdateShortcut(threadId)
                builder.setShortcutInfo(shortcut)
                notificationManager.notify(notificationId, builder.build())
                context.shortcutHelper.reportReceiveMessageUsage(threadId)
            }
        } else {
            builder.setShortcutInfo(shortcut)
            notificationManager.notify(notificationId, builder.build())
            ensureBackgroundThread {
                context.shortcutHelper.reportReceiveMessageUsage(threadId)
            }
        }
    }

    @SuppressLint("NewApi")
    fun showSendingFailedNotification(recipientName: String, threadId: Long) {
        val hasCustomNotifications =
            context.config.customNotifications.contains(threadId.toString())
        val notificationChannelId =
            if (hasCustomNotifications) threadId.toString() else NOTIFICATION_CHANNEL_ID
        if (!hasCustomNotifications) {
            createChannel(notificationChannelId, context.getString(R.string.message_not_sent_short))
        }

        val notificationId = generateRandomId().hashCode()
        val intent = Intent(context, ThreadActivity::class.java).apply {
            putExtra(THREAD_ID, threadId)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        val summaryText =
            String.format(context.getString(R.string.message_sending_error), recipientName)
        val largeIcon = Avatars.getLetterIcon(context, recipientName) // sms-man
        val builder = NotificationCompat.Builder(context, notificationChannelId)
            .setContentTitle(context.getString(R.string.message_not_sent_short))
            .setContentText(summaryText)
            .setColor(context.getProperPrimaryColor())
            .setSmallIcon(R.drawable.ic_messenger)
            .setLargeIcon(largeIcon)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summaryText))
            .setContentIntent(contentPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setDefaults(Notification.DEFAULT_LIGHTS)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setChannelId(notificationChannelId)

        notificationManager.notify(notificationId, builder.build())
    }

    private fun createChannel(id: String, name: String) {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setLegacyStreamType(AudioManager.STREAM_NOTIFICATION)
            .build()

        val importance = IMPORTANCE_HIGH
        NotificationChannel(id, name, importance).apply {
            setBypassDnd(false)
            enableLights(true)
            setSound(soundUri, audioAttributes)
            enableVibration(true)
            notificationManager.createNotificationChannel(this)
        }
    }

    /**
     * sms-man: bubbles were turned off. Remove the open bubbles now: if a bubble is replaced later by a
     * normal notification, Android removes the bubble and that new notification with it.
     */
    fun removeBubbles() {
        if (!isRPlus()) return
        notificationManager.activeNotifications
            .filter { it.notification.flags and Notification.FLAG_BUBBLE != 0 }
            .forEach { notificationManager.cancel(it.tag, it.id) }
    }

    /**
     * sms-man: remove the message notifications of threads that have no unread message any more (read in the
     * app, on another device, by another app...). Samsung shows these notifications as the app icon badge, so a
     * leftover one looks like an unread message that does not exist. Bubbles stay (closing one removes the bubble).
     * Call it on a background thread.
     */
    fun removeNotificationsOfReadThreads() {
        val active = notificationManager.activeNotifications.filter {
            val threadId = it.notification.shortcutId?.toLongOrNull() ?: return@filter false
            it.id == threadId.hashCode() && it.notification.flags and Notification.FLAG_BUBBLE == 0
        }
        if (active.isEmpty()) return

        val unreadThreadIds = try {
            getUnreadThreadIds()
        } catch (_: Exception) {
            return
        }
        active.filter { it.notification.shortcutId!!.toLong() !in unreadThreadIds }
            .forEach { notificationManager.cancel(it.tag, it.id) }
    }

    private fun getUnreadThreadIds(): Set<Long> {
        val ids = HashSet<Long>()
        val sms = Telephony.Sms.CONTENT_URI to
            "${Telephony.Sms.READ}=0 AND ${Telephony.Sms.TYPE}=${Telephony.Sms.MESSAGE_TYPE_INBOX}"
        val mms = Telephony.Mms.CONTENT_URI to
            "${Telephony.Mms.READ}=0 AND ${Telephony.Mms.MESSAGE_BOX}=${Telephony.Mms.MESSAGE_BOX_INBOX}"
        listOf(sms, mms).forEach { (uri, selection) ->
            // Sms.THREAD_ID and Mms.THREAD_ID are both "thread_id"
            context.contentResolver.query(uri, arrayOf(Telephony.Sms.THREAD_ID), selection, null, null)?.use { cursor ->
                while (cursor.moveToNext()) ids.add(cursor.getLong(0))
            }
        }
        return ids
    }

    // sms-man: the bubble opens BubbleThreadActivity (the conversation screen) over other apps
    private fun getBubbleMetadata(
        threadId: Long,
        notificationId: Int,
        avatar: Bitmap?
    ): NotificationCompat.BubbleMetadata {
        val intent = Intent(context, BubbleThreadActivity::class.java).apply {
            putExtra(THREAD_ID, threadId)
        }
        // Android 12+ requires a mutable PendingIntent for bubbles
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        val icon = if (avatar != null) {
            IconCompat.createWithAdaptiveBitmap(padForAdaptiveIcon(avatar))
        } else {
            IconCompat.createWithResource(context, R.mipmap.ic_launcher)
        }
        return NotificationCompat.BubbleMetadata.Builder(pendingIntent, icon)
            .setDesiredHeight(BUBBLE_HEIGHT_DP)
            .build()
    }

    /** Bubbles need an adaptive icon, which shows only the inner 72/108 part: add an empty border. */
    private fun padForAdaptiveIcon(bitmap: Bitmap): Bitmap {
        val size = maxOf(bitmap.width, bitmap.height) * 108 / 72
        val padded = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(padded).drawBitmap(
            bitmap,
            (size - bitmap.width) / 2f,
            (size - bitmap.height) / 2f,
            null
        )
        return padded
    }

    private fun getMessagesStyle(
        address: String,
        body: String,
        notificationId: Int,
        name: String?
    ): NotificationCompat.MessagingStyle {
        val sender = if (name != null) {
            Person.Builder()
                .setName(name)
                .setKey(address)
                .build()
        } else {
            null
        }

        return NotificationCompat.MessagingStyle(user).also { style ->
            getOldMessages(notificationId).forEach {
                style.addMessage(it)
            }
            val newMessage =
                NotificationCompat.MessagingStyle.Message(body, System.currentTimeMillis(), sender)
            style.addMessage(newMessage)
        }
    }

    private fun getOldMessages(notificationId: Int): List<NotificationCompat.MessagingStyle.Message> {
        val currentNotification =
            notificationManager.activeNotifications.find { it.id == notificationId }
        return if (currentNotification != null) {
            val activeStyle =
                NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(
                    currentNotification.notification
                )
            activeStyle?.messages.orEmpty()
        } else {
            emptyList()
        }
    }
}

package org.fossify.messages.helpers

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.AlarmManagerCompat
import org.fossify.commons.extensions.notificationManager
import org.fossify.messages.extensions.config
import org.fossify.messages.extensions.notificationHelper
import org.fossify.messages.receivers.RepeatNotificationReceiver

/**
 * sms-man: play the default notification sound again every N minutes (Settings → Notifications) while a
 * new-message notification of an unread conversation is still shown. It stops when the messages are read or the
 * notification is swiped away. Silent mode, vibrate mode (vibrates only) and Do Not Disturb are respected.
 * The timer starts again with every new message (that message already played the sound).
 */
object RepeatNotificationSound {
    private const val REQUEST_CODE = 7_301
    private const val MAX_PLAY_MS = 8_000L

    /** (Re)start the timer: the next check is in N minutes. Does nothing when the setting is off. */
    fun schedule(context: Context) {
        val config = context.config
        if (!config.repeatNotificationSound) {
            cancel(context)
            return
        }

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAt = SystemClock.elapsedRealtime() + config.repeatNotificationMinutes * 60_000L
        val pendingIntent = getPendingIntent(context)
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
        if (canExact) {
            AlarmManagerCompat.setExactAndAllowWhileIdle(alarmManager, AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent)
        } else {
            AlarmManagerCompat.setAndAllowWhileIdle(alarmManager, AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent)
        }
    }

    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(getPendingIntent(context))
    }

    /**
     * The alarm fired: play the sound if something is still unread and not dismissed, then plan the next one.
     * Blocking (waits for the sound, at most a few seconds); call it from a background thread.
     */
    fun onAlarm(context: Context) {
        if (!context.config.repeatNotificationSound) return
        if (!context.notificationHelper.hasUnreadMessageNotification()) return

        schedule(context)
        alert(context)
    }

    private fun alert(context: Context) {
        val filter = context.notificationManager.currentInterruptionFilter
        if (filter != NotificationManager.INTERRUPTION_FILTER_ALL && filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN) {
            return // Do Not Disturb
        }

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        when (audioManager.ringerMode) {
            AudioManager.RINGER_MODE_NORMAL -> playSound(context)
            AudioManager.RINGER_MODE_VIBRATE -> vibrate(context)
            else -> Unit // silent
        }
    }

    private fun playSound(context: Context) {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) ?: return
        val ringtone = RingtoneManager.getRingtone(context, uri) ?: return
        ringtone.audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        ringtone.play()
        // the sound plays in this process: keep the receiver alive until it ends
        val end = SystemClock.elapsedRealtime() + MAX_PLAY_MS
        Thread.sleep(300)
        while (ringtone.isPlaying && SystemClock.elapsedRealtime() < end) {
            Thread.sleep(200)
        }
        ringtone.stop()
    }

    @Suppress("DEPRECATION")
    private fun vibrate(context: Context) {
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (!vibrator.hasVibrator()) return
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 200, 300), -1))
    }

    private fun getPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, RepeatNotificationReceiver::class.java)
        return PendingIntent.getBroadcast(
            context, REQUEST_CODE, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

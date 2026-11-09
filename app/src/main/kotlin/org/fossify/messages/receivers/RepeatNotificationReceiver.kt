package org.fossify.messages.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.messages.helpers.RepeatNotificationSound

/** sms-man: the "repeat the notification sound" timer fired. See [RepeatNotificationSound]. */
class RepeatNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val appContext = context.applicationContext
        ensureBackgroundThread {
            try {
                RepeatNotificationSound.onAlarm(appContext)
            } catch (_: Exception) {
            } finally {
                pending.finish()
            }
        }
    }
}

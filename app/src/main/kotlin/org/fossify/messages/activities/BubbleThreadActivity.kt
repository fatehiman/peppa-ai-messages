package org.fossify.messages.activities

/**
 * sms-man: the same conversation screen, opened inside a floating chat bubble (Android 11+).
 * It is a separate activity because Android needs special manifest flags for bubbles
 * (allowEmbedded, resizeableActivity, documentLaunchMode="always"), and the normal
 * [ThreadActivity] must keep its own launch behavior.
 */
class BubbleThreadActivity : ThreadActivity() {
    override val isInBubble = true
}

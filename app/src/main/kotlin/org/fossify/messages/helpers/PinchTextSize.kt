package org.fossify.messages.helpers

import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.recyclerview.widget.RecyclerView
import org.fossify.messages.extensions.config
import kotlin.math.abs
import kotlin.reflect.KMutableProperty0

/**
 * sms-man: change the text size of a list with two fingers (pinch), like Google Messages.
 *
 * The size is "Font size" (Settings) × a saved scale. The conversation screen and the conversation
 * lists have their own scale. Choosing a font size in Settings sets both scales back to 1.
 */
object PinchTextSize {
    const val MIN_SCALE = 0.7f
    const val MAX_SCALE = 2.5f

    /** Adapter payload: only the text size changed, so only the text sizes of the item are set again. */
    object Payload

    /**
     * Pinch on [recyclerView] changes [savedScale]. [onScale] shows a new scale while the fingers move;
     * the scale is saved when they are lifted. Does nothing while [Config.pinchTextSize] is off.
     */
    fun setup(recyclerView: RecyclerView, savedScale: KMutableProperty0<Float>, onScale: (Float) -> Unit) {
        var scale = 1f
        val listener = object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                scale = savedScale.get()
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val newScale = (scale * detector.scaleFactor).coerceIn(MIN_SCALE, MAX_SCALE)
                // false = the small change is added to the next one, so the list is not redrawn too often
                if (abs(newScale - scale) < 0.02f) return false
                scale = newScale
                onScale(scale)
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                savedScale.set(scale)
            }
        }

        val detector = ScaleGestureDetector(recyclerView.context, listener).apply {
            isQuickScaleEnabled = false
        }

        recyclerView.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                if (!rv.context.config.pinchTextSize) return false
                detector.onTouchEvent(e)
                // a second finger: the list stops scrolling and the rest of the gesture comes to onTouchEvent
                return e.pointerCount > 1
            }

            override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
                detector.onTouchEvent(e)
            }
        })
    }

    /** True when [payloads] ask only for a new text size. */
    fun isOnlyTextSize(payloads: List<Any>) = payloads.isNotEmpty() && payloads.all { it == Payload }
}

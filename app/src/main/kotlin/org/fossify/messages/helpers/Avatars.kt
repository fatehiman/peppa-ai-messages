package org.fossify.messages.helpers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.widget.ImageView
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.signature.ObjectKey
import org.fossify.commons.extensions.getContrastColor
import org.fossify.commons.extensions.getNameLetter
import org.fossify.commons.helpers.letterBackgroundColors
import kotlin.math.abs

/**
 * sms-man: icons for numbers that are not saved as contacts. Fossify draws the first letter of the name,
 * which is "+" for every "+98..." number. Instead we show, in this order:
 *  1. the Telegram profile picture (if turned on and already downloaded, see [TelegramAvatars])
 *  2. the first letter of the Samsung call log note of the number (see [CallLogNotes])
 *  3. the first digits of the number, without the country code: "+989133419544" -> "913"
 * Saved contacts and text senders ("BANK") keep the normal first letter.
 */
object Avatars {
    private const val LABEL_DIGITS = 3
    private val PHONE_LIKE = Regex("^\\+?[0-9][0-9 ()\\-]{2,}$")

    fun isPhoneLike(text: String) = PHONE_LIKE.matches(text.trim())

    /** The text in the circle. [name] is what the app shows (the number itself when it is not a contact). */
    fun getLabel(context: Context, name: String, phoneNumber: String? = null): String {
        if (!isPhoneLike(name)) return name.getNameLetter()
        val number = phoneNumber?.takeIf { it.isNotBlank() } ?: name
        CallLogNotes.get(context, number)?.let { return it.getNameLetter() }
        return numberLabel(number) ?: name.getNameLetter()
    }

    /** "+989133419544" / "09133419544" / "00989133419544" -> "913", "+60412345678" -> "604", "50001234" -> "500" */
    fun numberLabel(number: String): String? {
        val trimmed = number.trim()
        val digits = trimmed.filter { it.isDigit() }
        val national = when {
            trimmed.startsWith("+98") -> digits.removePrefix("98")
            digits.startsWith("0098") -> digits.removePrefix("0098")
            trimmed.startsWith("+") -> digits
            digits.startsWith("00") -> digits.removePrefix("00")
            digits.startsWith("0") -> digits.removePrefix("0")
            else -> digits
        }
        return national.take(LABEL_DIGITS).ifEmpty { null }
    }

    /** Same look as Fossify's letter icon, with smaller text for 2-3 digits. */
    fun getLetterIcon(context: Context, name: String, phoneNumber: String? = null): Bitmap {
        val label = getLabel(context, name, phoneNumber)
        val size = context.resources.getDimension(org.fossify.commons.R.dimen.normal_icon_size).toInt()
        val bitmap = createBitmap(size, size)
        val canvas = Canvas(bitmap)

        val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = letterBackgroundColors[abs(name.hashCode()) % letterBackgroundColors.size].toInt()
        }
        val scale = when {
            label.length >= 3 && label.all { it.isDigit() } -> 0.32f
            label.length == 2 && label.all { it.isDigit() } -> 0.4f
            else -> 0.5f
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = circlePaint.color.getContrastColor()
            textAlign = Paint.Align.CENTER
            textSize = size * scale
            style = Paint.Style.FILL
        }

        canvas.drawCircle(size / 2f, size / 2f, size / 2f, circlePaint)
        val yPos = size / 2f - (textPaint.descent() + textPaint.ascent()) / 2
        canvas.drawText(label, size / 2f, yPos, textPaint)
        return bitmap
    }

    /**
     * Replaces SimpleContactsHelper.loadContactImage: [photoUri] (contact photo), else the Telegram picture,
     * else the letter icon. A [placeholderImage] (group icon) disables the Telegram picture.
     * [name] that is not a number = a saved contact (Telegram picture only if that setting is on).
     */
    fun load(
        context: Context,
        imageView: ImageView,
        photoUri: String,
        name: String,
        phoneNumber: String? = null,
        placeholderImage: Drawable? = null,
    ) {
        val placeholder = placeholderImage ?: getLetterIcon(context, name, phoneNumber).toDrawable(context.resources)
        var model: Any? = photoUri.ifEmpty { null }
        var signature: ObjectKey? = null
        if (model == null && placeholderImage == null) {
            getTelegramFile(context, name, phoneNumber)?.let {
                model = it
                signature = ObjectKey(it.lastModified()) // a new download must not show the old picture
            }
        }

        val options = RequestOptions()
            .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
            .error(placeholder)
            .centerCrop()

        var request = Glide.with(context)
            .load(model)
            .transition(DrawableTransitionOptions.withCrossFade())
            .placeholder(placeholder)
            .apply(options)
            .apply(RequestOptions.circleCropTransform())
        signature?.let { request = request.signature(it) }
        request.into(imageView)
    }

    /** For notifications and shortcuts (no ImageView). Call it from a background thread. */
    fun getBitmap(context: Context, name: String, phoneNumber: String?): Bitmap {
        getTelegramFile(context, name, phoneNumber)?.let { file ->
            try {
                BitmapFactory.decodeFile(file.absolutePath)?.let { return it }
            } catch (_: Exception) {
            }
        }
        return getLetterIcon(context, name, phoneNumber)
    }

    /** The Telegram picture for a non-contact ([name] is the number) or a contact (needs [phoneNumber]). */
    fun getTelegramFile(context: Context, name: String, phoneNumber: String?): java.io.File? {
        val isContact = !isPhoneLike(name)
        val number = phoneNumber?.ifBlank { null } ?: if (isContact) return null else name
        return TelegramAvatars.getCachedFile(context, number, isContact)
    }
}

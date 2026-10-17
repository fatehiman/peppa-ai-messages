package org.fossify.messages.helpers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import android.util.Log
import org.fossify.messages.extensions.config
import org.fossify.messages.models.Events
import org.greenrobot.eventbus.EventBus
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * sms-man: profile pictures from Telegram (off by default), for numbers that are not contacts and/or for
 * contacts without their own photo (two settings).
 *
 * API (Telegram Manager): GET <url>/phone/<+number>/photo?format=image, "Authorization: Bearer <token>".
 * 200 = JPEG, 404 = no Telegram user / no picture, 429 / 503 = wait (Retry-After).
 *
 * - Only numbers that start with one of the prefixes in the settings (default +989, Iran mobile).
 * - Only when a SMS arrives and when a conversation is opened, never while scrolling the list,
 *   so Telegram is not asked for many numbers at once.
 * - One request at a time, in the background. The answer (picture or "no picture") is kept for [KEEP_DAYS].
 * - Pictures are files in filesDir/telegram_avatars/<digits>.jpg (made smaller: at most [MAX_SIZE_PX] px,
 *   about 10-20 KB); "no picture" is an empty <digits>.none.
 * - "Seen": every time a picture is shown, the number is remembered in memory; [flushSeen] then touches
 *   <digits>.used. Once a day [cleanupIfNeeded] deletes the files of numbers not seen or asked for
 *   [CLEANUP_DAYS] days. If such a number comes back, its picture is downloaded again.
 */
object TelegramAvatars {
    private const val TAG = "TelegramAvatars"
    private const val DIR = "telegram_avatars"
    private const val KEEP_DAYS = 20
    private const val KEEP_MS = KEEP_DAYS * 24 * 60 * 60 * 1000L
    private const val CONNECT_TIMEOUT_MS = 20_000
    private const val READ_TIMEOUT_MS = 60_000
    private const val ERROR_RETRY_MS = 60 * 60 * 1000L // a failed number is tried again after 1 hour
    private const val DEFAULT_PAUSE_MS = 10 * 60 * 1000L // after 429 / 503 without Retry-After
    private const val AUTH_PAUSE_MS = 60 * 60 * 1000L // after 401 / 403
    private const val MAX_BYTES = 5 * 1024 * 1024
    private const val MAX_SIZE_PX = 256
    private const val JPEG_QUALITY = 85
    private const val CLEANUP_DAYS = 90
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    private val executor = Executors.newSingleThreadExecutor()
    private val queued = Collections.synchronizedSet(HashSet<String>())
    private val lastError = ConcurrentHashMap<String, Long>()
    private val seen = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var pausedUntil = 0L

    @Volatile
    private var countryIso: String? = null

    /** Is the feature on for this kind of number? [isContact] = a saved contact (without its own photo). */
    fun isEnabledFor(context: Context, isContact: Boolean): Boolean {
        val config = context.config
        return if (isContact) config.telegramAvatarsContacts else config.telegramAvatarsEnabled
    }

    private fun hasApi(context: Context): Boolean {
        val config = context.config
        return config.telegramApiUrl.isNotEmpty() && config.telegramApiToken.isNotEmpty()
    }

    /** The downloaded picture of [number], or null. Fast (one file check). Old pictures are still shown. */
    fun getCachedFile(context: Context, number: String, isContact: Boolean): File? {
        if (!isEnabledFor(context, isContact)) return null
        val international = toInternational(context, number) ?: return null
        val file = photoFile(context, international).takeIf { it.exists() && it.length() > 0 } ?: return null
        seen.add(international)
        return file
    }

    /** Download the picture in the background if there is no answer younger than [KEEP_DAYS]. Never blocks. */
    fun fetchInBackground(context: Context, number: String, isContact: Boolean) {
        val appContext = context.applicationContext
        if (!isEnabledFor(appContext, isContact) || !hasApi(appContext)) return
        val international = toInternational(appContext, number) ?: return
        if (!matchesPrefixes(appContext, international)) return
        val now = System.currentTimeMillis()
        if (now < pausedUntil || now - (lastError[international] ?: 0L) < ERROR_RETRY_MS) return
        if (!queued.add(international)) return

        seen.add(international)
        executor.execute {
            try {
                if (!isFresh(appContext, international)) fetch(appContext, international)
            } catch (e: Exception) {
                lastError[international] = System.currentTimeMillis()
                Log.w(TAG, "fetch failed: $e")
            } finally {
                queued.remove(international)
            }
        }
    }

    /** Settings → Test the connection: GET <url>/me. Blocking. @return (true, token name) or (false, error) */
    fun test(context: Context): Pair<Boolean, String> {
        val config = context.config
        return try {
            val conn = open("${config.telegramApiUrl}/me", config.telegramApiToken)
            try {
                val code = conn.responseCode
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code in 200..299) {
                    val token = JSONObject(text).optJSONObject("token")
                    true to (token?.optString("name").orEmpty() + " (" + token?.optString("account_status").orEmpty() + ")")
                } else {
                    false to "HTTP $code ${errorCode(text)}".trim()
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            false to (e.message ?: e.javaClass.simpleName)
        }
    }

    /** Save the "seen" times kept in memory (touches <digits>.used). Blocking; call it from a background thread. */
    fun flushSeen(context: Context) {
        if (seen.isEmpty()) return
        val now = System.currentTimeMillis()
        val numbers = seen.toList()
        seen.removeAll(numbers.toSet())
        numbers.forEach { international ->
            try {
                val file = usedFile(context, international)
                if (!file.exists()) file.writeBytes(ByteArray(0))
                file.setLastModified(now)
            } catch (_: Exception) {
            }
        }
    }

    /** Once a day, in the background: delete the files of numbers not seen or asked for [CLEANUP_DAYS] days. */
    fun cleanupIfNeeded(context: Context) {
        val appContext = context.applicationContext
        val config = appContext.config
        val now = System.currentTimeMillis()
        if (now - config.lastTelegramAvatarsCleanup < DAY_MS) return
        config.lastTelegramAvatarsCleanup = now
        executor.execute {
            try {
                flushSeen(appContext)
                val files = File(appContext.filesDir, DIR).listFiles() ?: return@execute
                var deleted = 0
                files.groupBy { it.name.substringBefore('.') }.values.forEach { group ->
                    val lastActivity = group.maxOf { it.lastModified() }
                    if (now - lastActivity > CLEANUP_DAYS * DAY_MS) {
                        group.forEach { if (it.delete()) deleted++ }
                    }
                }
                if (deleted > 0) Log.i(TAG, "cleanup: deleted $deleted old files")
            } catch (e: Exception) {
                Log.w(TAG, "cleanup failed: $e")
            }
        }
    }

    /** Forget all pictures (for example after the API changed). */
    fun clearCache(context: Context) {
        File(context.filesDir, DIR).listFiles()?.forEach { it.delete() }
        lastError.clear()
        pausedUntil = 0L
    }

    private fun fetch(context: Context, international: String) {
        if (!hasApi(context) || System.currentTimeMillis() < pausedUntil) return
        val config = context.config
        val url = "${config.telegramApiUrl}/phone/${URLEncoder.encode(international, "UTF-8")}/photo?format=image"
        val conn = open(url, config.telegramApiToken)
        try {
            val code = conn.responseCode
            when {
                code == HttpURLConnection.HTTP_OK -> {
                    val bytes = conn.inputStream.use { it.readBytes() }
                    val small = if (bytes.size > MAX_BYTES) null else shrink(bytes)
                    if (small == null) {
                        lastError[international] = System.currentTimeMillis()
                        return
                    }
                    val file = photoFile(context, international)
                    val tmp = File(file.parentFile, file.name + ".tmp")
                    tmp.outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
                    small.recycle()
                    tmp.renameTo(file)
                    noneFile(context, international).delete()
                    Log.i(TAG, "picture saved for $international")
                    EventBus.getDefault().post(Events.AvatarsChanged())
                }

                // no Telegram user, no picture, or a bad number: remember it, do not ask again for KEEP_DAYS
                code == HttpURLConnection.HTTP_NOT_FOUND || code == 422 -> {
                    val hadPicture = photoFile(context, international).delete()
                    noneFile(context, international).apply {
                        writeBytes(ByteArray(0))
                        setLastModified(System.currentTimeMillis())
                    }
                    if (hadPicture) EventBus.getDefault().post(Events.AvatarsChanged())
                }

                code == 429 || code == HttpURLConnection.HTTP_UNAVAILABLE -> {
                    val retryAfter = conn.getHeaderField("Retry-After")?.toLongOrNull()
                    pausedUntil = System.currentTimeMillis() + (retryAfter?.times(1000) ?: DEFAULT_PAUSE_MS)
                    lastError[international] = System.currentTimeMillis()
                }

                code == HttpURLConnection.HTTP_UNAUTHORIZED || code == HttpURLConnection.HTTP_FORBIDDEN -> {
                    pausedUntil = System.currentTimeMillis() + AUTH_PAUSE_MS
                }

                else -> lastError[international] = System.currentTimeMillis()
            }
        } finally {
            conn.disconnect()
        }
    }

    /** Decode and make it at most [MAX_SIZE_PX] px (the icons are about 50-60 dp). null = not a picture. */
    private fun shrink(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIZE_PX) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        val scale = MAX_SIZE_PX.toFloat() / maxOf(decoded.width, decoded.height)
        if (scale >= 1f) return decoded
        val width = (decoded.width * scale).toInt().coerceAtLeast(1)
        val height = (decoded.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(decoded, width, height, true)
        if (scaled != decoded) decoded.recycle()
        return scaled
    }

    private fun open(url: String, token: String): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Authorization", "Bearer $token")
        }
    }

    private fun errorCode(text: String): String = try {
        JSONObject(text).optJSONObject("error")?.optString("code").orEmpty()
    } catch (_: Exception) {
        ""
    }

    private fun isFresh(context: Context, international: String): Boolean {
        val now = System.currentTimeMillis()
        return listOf(photoFile(context, international), noneFile(context, international)).any {
            it.exists() && now - it.lastModified() < KEEP_MS
        }
    }

    private fun photoFile(context: Context, international: String) = File(dir(context), "${international.drop(1)}.jpg")

    private fun noneFile(context: Context, international: String) = File(dir(context), "${international.drop(1)}.none")

    private fun usedFile(context: Context, international: String) = File(dir(context), "${international.drop(1)}.used")

    private fun dir(context: Context) = File(context.filesDir, DIR).apply { mkdirs() }

    private fun matchesPrefixes(context: Context, international: String): Boolean {
        return context.config.telegramPrefixes.split(',', ' ', ';')
            .mapNotNull { normalizePrefix(it) }
            .any { international.startsWith(it) }
    }

    /** "+9891" / "009891" / "9891" -> "+9891" */
    private fun normalizePrefix(prefix: String): String? {
        val digits = prefix.trim().filter { it.isDigit() }.removePrefix("00")
        return if (digits.isEmpty()) null else "+$digits"
    }

    /** "+98 913 ..." / "00989..." / "0913..." (local) -> "+98913...". Short codes and text senders -> null. */
    private fun toInternational(context: Context, number: String): String? {
        val trimmed = number.trim()
        if (!Avatars.isPhoneLike(trimmed)) return null
        val digits = trimmed.filter { it.isDigit() }
        return when {
            trimmed.startsWith("+") -> "+$digits"
            digits.startsWith("00") -> "+" + digits.drop(2)
            digits.startsWith("0") -> PhoneNumberUtils.formatNumberToE164(digits, getCountryIso(context))
            else -> null
        }
    }

    private fun getCountryIso(context: Context): String {
        countryIso?.let { return it }
        val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val iso = (telephony?.simCountryIso?.ifEmpty { null } ?: telephony?.networkCountryIso?.ifEmpty { null }
            ?: Locale.getDefault().country).uppercase(Locale.US)
        countryIso = iso
        return iso
    }
}

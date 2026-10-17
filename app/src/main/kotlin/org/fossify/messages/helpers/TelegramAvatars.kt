package org.fossify.messages.helpers

import android.content.Context
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
 * sms-man: profile pictures from Telegram for numbers that are not contacts (off by default).
 *
 * API (Telegram Manager): GET <url>/phone/<+number>/photo?format=image, "Authorization: Bearer <token>".
 * 200 = JPEG, 404 = no Telegram user / no picture, 429 / 503 = wait (Retry-After).
 *
 * - Only numbers that start with one of the prefixes in the settings (default +989, Iran mobile).
 * - Only when a SMS arrives and when a conversation is opened, never while scrolling the list,
 *   so Telegram is not asked for many numbers at once.
 * - One request at a time, in the background. The answer (picture or "no picture") is kept for [KEEP_DAYS].
 * - Pictures are files in filesDir/telegram_avatars/<digits>.jpg; "no picture" is an empty <digits>.none.
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

    private val executor = Executors.newSingleThreadExecutor()
    private val queued = Collections.synchronizedSet(HashSet<String>())
    private val lastError = ConcurrentHashMap<String, Long>()

    @Volatile
    private var pausedUntil = 0L

    @Volatile
    private var countryIso: String? = null

    fun isConfigured(context: Context): Boolean {
        val config = context.config
        return config.telegramAvatarsEnabled && config.telegramApiUrl.isNotEmpty() && config.telegramApiToken.isNotEmpty()
    }

    /** The downloaded picture of [number], or null. Fast (one file check). Old pictures are still shown. */
    fun getCachedFile(context: Context, number: String): File? {
        if (!context.config.telegramAvatarsEnabled) return null
        val international = toInternational(context, number) ?: return null
        return photoFile(context, international).takeIf { it.exists() && it.length() > 0 }
    }

    /** Download the picture in the background if there is no answer younger than [KEEP_DAYS]. Never blocks. */
    fun fetchInBackground(context: Context, number: String) {
        val appContext = context.applicationContext
        if (!isConfigured(appContext)) return
        val international = toInternational(appContext, number) ?: return
        if (!matchesPrefixes(appContext, international)) return
        val now = System.currentTimeMillis()
        if (now < pausedUntil || now - (lastError[international] ?: 0L) < ERROR_RETRY_MS) return
        if (!queued.add(international)) return

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

    /** Forget all pictures (for example after the API changed). */
    fun clearCache(context: Context) {
        File(context.filesDir, DIR).listFiles()?.forEach { it.delete() }
        lastError.clear()
        pausedUntil = 0L
    }

    private fun fetch(context: Context, international: String) {
        if (!isConfigured(context) || System.currentTimeMillis() < pausedUntil) return
        val config = context.config
        val url = "${config.telegramApiUrl}/phone/${URLEncoder.encode(international, "UTF-8")}/photo?format=image"
        val conn = open(url, config.telegramApiToken)
        try {
            val code = conn.responseCode
            when {
                code == HttpURLConnection.HTTP_OK -> {
                    val bytes = conn.inputStream.use { it.readBytes() }
                    if (bytes.size > MAX_BYTES || BitmapFactory.decodeByteArray(bytes, 0, bytes.size) == null) {
                        lastError[international] = System.currentTimeMillis()
                        return
                    }
                    val file = photoFile(context, international)
                    val tmp = File(file.parentFile, file.name + ".tmp")
                    tmp.writeBytes(bytes)
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

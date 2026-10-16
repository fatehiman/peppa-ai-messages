package org.fossify.messages.helpers

import android.content.Context
import org.fossify.commons.extensions.baseConfig
import org.fossify.commons.extensions.getMyContactsCursor
import org.fossify.commons.extensions.isNumberBlocked
import org.fossify.commons.helpers.ContactLookupResult
import org.fossify.commons.helpers.SimpleContactsHelper
import org.fossify.messages.extensions.config
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * sms-man: asks the spam API (e.g. https://notes.peppasoft.com/api/sms/check) whether an SMS is spam/ads.
 *
 * Request:  POST <url>, "Authorization: Bearer <token>", JSON {"sender": "...", "body": "..."}
 * Answer:   JSON {"ok": true, "spam": true, "reason": "ai", "percent": 92}
 * Feedback: POST <url>/../feedback, JSON {"sender": "...", "spam": true|false}
 *
 * Fail-open: if the API is slow, offline or answers badly, the SMS goes to the normal inbox.
 */
object SpamFilter {
    private const val CONNECT_TIMEOUT_MS = 4_000
    private const val READ_TIMEOUT_MS = 7_000

    // The SMS receiver must finish in about 10 s, so it never waits longer than this.
    private const val CHECK_DEADLINE_MS = 8_000L

    // Phone numbers are compared by their last 10 digits (shorter ones are short codes: exact match only).
    private const val PHONE_DIGITS = 10

    // Reasons saved with a spam message. The API adds its own: sender, phrase, ai.
    const val REASON_REPORT = "report"
    const val REASON_BLOCKED_NUMBER = "blocked_number"
    const val REASON_BLOCKED_KEYWORD_PREFIX = "keyword:"
    const val REASON_UNKNOWN_NUMBER = "unknown_number"

    private val executor = Executors.newCachedThreadPool()

    sealed class Result {
        data class Spam(val reason: String, val percent: Int?) : Result()
        data class NotSpam(val reason: String, val percent: Int?) : Result()
        data class Error(val message: String) : Result()
    }

    // A login / verification code: a code word plus 4..8 digits (Latin, Persian or Arabic digits).
    // "کد تخفیف" (discount code) is NOT a code word on purpose: it is usually an ad.
    private val CODE_WORDS = Regex(
        "(?i)(otp|verification|verify|one[- ]?time|passcode|password|login code|security code|رمز|" +
            "کد\\s*(تایید|تأیید|ورود|فعال\\s*سازی|فعالسازی|یکبار|امنیتی|احراز))"
    )
    private val CODE_DIGITS = Regex("[0-9۰-۹٠-٩]{4,8}")

    fun normalizeSender(address: String) =
        address.filterNot { it == ' ' || it == '-' }.removePrefix("+").lowercase()

    fun looksLikeCode(body: String) = CODE_WORDS.containsMatchIn(body) && CODE_DIGITS.containsMatchIn(body)

    /**
     * Is [address] in the "allowed numbers" list? Exact match (after [normalizeSender]), or for phone
     * numbers the same last 10 digits, so 09121234567 matches +989121234567. Short codes: exact only.
     */
    fun isAllowedNumber(context: Context, address: String): Boolean {
        val sender = normalizeSender(address)
        return context.config.allowedNumbers.any { entry ->
            val allowed = normalizeSender(entry)
            allowed == sender || (allowed.length >= PHONE_DIGITS && sender.length >= PHONE_DIGITS &&
                allowed.all { it.isDigit() } && sender.all { it.isDigit() } &&
                allowed.takeLast(PHONE_DIGITS) == sender.takeLast(PHONE_DIGITS))
        }
    }

    sealed class Decision {
        object Inbox : Decision()
        data class Spam(val reason: String, val percent: Int?) : Decision()
    }

    /**
     * Where does a new incoming SMS go? Blocking (it may call the API); call it from a background thread.
     * The order of the rules matters: "allowed" rules come before "blocked" rules.
     *  1. contact (setting, on by default)               -> inbox
     *  2. allowed number / allowed keyword               -> inbox
     *  3. looks like a login code (setting, on)          -> inbox
     *  4. blocked number / blocked keyword /
     *     "block unknown numbers" + not a contact        -> spam folder
     *  5. spam filter off or not set up                  -> inbox
     *  6. the API: spam -> spam folder; not spam, error, timeout (8 s), no internet -> inbox
     * Numbers in Android's system block list never get here: Android drops those SMS itself.
     */
    fun decide(context: Context, address: String, body: String): Decision {
        val config = context.config
        val contactResult by lazy {
            context.getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true).use {
                SimpleContactsHelper(context).existsSync(address, it)
            }
        }

        val isAllowed = when {
            config.spamSkipContacts && contactResult is ContactLookupResult.Found -> true
            isAllowedNumber(context, address) -> true
            config.allowedKeywords.any { it.isNotEmpty() && body.contains(it, ignoreCase = true) } -> true
            config.spamSkipCodes && looksLikeCode(body) -> true
            else -> false
        }
        if (isAllowed) return Decision.Inbox

        if (context.isNumberBlocked(address)) return Decision.Spam(REASON_BLOCKED_NUMBER, null)
        ReceiverUtils.findBlockedKeyword(context, body)?.let {
            return Decision.Spam(REASON_BLOCKED_KEYWORD_PREFIX + it, null)
        }
        if (context.baseConfig.blockUnknownNumbers && contactResult == ContactLookupResult.NotFound) {
            return Decision.Spam(REASON_UNKNOWN_NUMBER, null)
        }

        if (!config.isSpamFilterConfigured) return Decision.Inbox
        return when (val result = check(context, address, body)) {
            is Result.Spam -> Decision.Spam(result.reason, result.percent)
            else -> Decision.Inbox
        }
    }

    /** Blocking; waits at most [CHECK_DEADLINE_MS]. Call it from a background thread. */
    fun check(context: Context, address: String, body: String): Result {
        val config = context.config
        val json = JSONObject().put("sender", address).put("body", body)
        val future = executor.submit<Result> {
            val (code, text) = post(config.spamApiUrl, config.spamApiToken, json)
            parseCheck(code, text)
        }
        return try {
            future.get(CHECK_DEADLINE_MS, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            Result.Error("timeout")
        } catch (e: Exception) {
            Result.Error(e.cause?.message ?: e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Tell the server that [address] is (or is not) a spammer. Blocking; call it from a background thread.
     * @return null on success, else an error message
     */
    fun sendFeedback(context: Context, address: String, body: String, spam: Boolean): String? {
        val config = context.config
        if (config.spamApiUrl.isEmpty() || config.spamApiToken.isEmpty()) return "not configured"
        return try {
            val json = JSONObject().put("sender", address).put("body", body).put("spam", spam)
            val (code, text) = post(feedbackUrl(config.spamApiUrl), config.spamApiToken, json)
            if (code in 200..299) null else errorText(code, text)
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }
    }

    /** ".../api/sms/check" -> ".../api/sms/feedback" */
    fun feedbackUrl(checkUrl: String): String = URI(checkUrl).resolve("feedback").toString()

    private fun parseCheck(code: Int, text: String): Result {
        if (code !in 200..299) return Result.Error(errorText(code, text))
        return try {
            val json = JSONObject(text)
            val reason = json.optString("reason", "none")
            val percent = if (json.isNull("percent")) null else json.optInt("percent")
            when {
                !json.has("spam") -> Result.Error("no \"spam\" field in the answer")
                json.getBoolean("spam") -> Result.Spam(reason, percent)
                else -> Result.NotSpam(reason, percent)
            }
        } catch (e: Exception) {
            Result.Error("bad answer: ${e.message}")
        }
    }

    private fun errorText(code: Int, text: String): String {
        val desc = try {
            JSONObject(text).optString("errDesc")
        } catch (_: Exception) {
            ""
        }
        return "HTTP $code" + if (desc.isNotEmpty()) ": $desc" else ""
    }

    private fun post(url: String, token: String, json: JSONObject): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.outputStream.use { it.write(json.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            return code to text
        } finally {
            conn.disconnect()
        }
    }
}

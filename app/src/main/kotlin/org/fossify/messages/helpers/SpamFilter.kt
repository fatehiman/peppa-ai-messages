package org.fossify.messages.helpers

import android.content.Context
import android.net.Uri
import android.provider.BaseColumns
import android.provider.Telephony
import org.fossify.commons.extensions.baseConfig
import org.fossify.commons.extensions.getBlockedNumbers
import org.fossify.commons.extensions.getMyContactsCursor
import org.fossify.commons.extensions.isNumberBlocked
import org.fossify.commons.helpers.ContactLookupResult
import org.fossify.commons.helpers.SimpleContactsHelper
import org.fossify.messages.extensions.config
import org.fossify.messages.extensions.conversationsDB
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
    // Feedback calls are not urgent, they use fixed timeouts.
    private const val FEEDBACK_TIMEOUT_MS = 15_000

    // check() waits at most config.spamApiTimeoutSecs (8-30 s, default 15). The SMS receiver runs as a
    // background broadcast, which Android allows about 60 s, so 30 s is safe.

    // Phone numbers are compared by their last 10 digits (shorter ones are short codes: exact match only).
    private const val PHONE_DIGITS = 10
    private const val DAY_MS = 24L * 60 * 60 * 1000

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
    fun isAllowedNumber(context: Context, address: String): Boolean =
        context.config.allowedNumbers.any { NumberRules.matches(it, address) }

    /** Blocked numbers incl. prefixes like 091* (Android's block list itself only knows exact numbers). */
    private fun isBlockedByPrefix(context: Context, address: String): Boolean = try {
        context.getBlockedNumbers().any { NumberRules.matches(it.number, address) }
    } catch (_: Exception) {
        false
    }

    /**
     * Is this sender someone I talk to? true when a conversation with the sender exists (also archived) and
     *  - I sent a message in it (any age; a number I wrote to is a real person), or
     *  - it has a message from the last [Config.spamSkipConversationDays] days (0 = any age).
     * An old conversation that has only old received texts does not count: it can be an old spam that came
     * before this app was installed.
     */
    fun hasConversationWith(context: Context, address: String): Boolean = try {
        val threadIds = context.conversationsDB.getAllThreadNumbers().filter { conv ->
            conv.phoneNumber.split(',').any { it.isNotBlank() && NumberRules.matches(it.trim(), address) }
        }.map { it.threadId }.distinct()

        when {
            threadIds.isEmpty() -> false
            hasSentMessage(context, threadIds) -> true
            else -> {
                val days = context.config.spamSkipConversationDays
                days == 0 || hasMessageSince(context, threadIds, System.currentTimeMillis() - days * DAY_MS)
            }
        }
    } catch (_: Exception) {
        false
    }

    private fun hasSentMessage(context: Context, threadIds: List<Long>): Boolean {
        val inThreads = "${Telephony.Sms.THREAD_ID} IN (${threadIds.joinToString(",")})"
        val sentSms = listOf(
            Telephony.Sms.MESSAGE_TYPE_SENT, Telephony.Sms.MESSAGE_TYPE_OUTBOX, Telephony.Sms.MESSAGE_TYPE_QUEUED
        ).joinToString(",")
        return exists(context, Telephony.Sms.CONTENT_URI, "$inThreads AND ${Telephony.Sms.TYPE} IN ($sentSms)") ||
            exists(context, Telephony.Mms.CONTENT_URI, "$inThreads AND ${Telephony.Mms.MESSAGE_BOX}=${Telephony.Mms.MESSAGE_BOX_SENT}")
    }

    private fun hasMessageSince(context: Context, threadIds: List<Long>, sinceMs: Long): Boolean {
        val inThreads = "${Telephony.Sms.THREAD_ID} IN (${threadIds.joinToString(",")})"
        // SMS dates are in milliseconds, MMS dates in seconds
        return exists(context, Telephony.Sms.CONTENT_URI, "$inThreads AND ${Telephony.Sms.DATE} >= $sinceMs") ||
            exists(context, Telephony.Mms.CONTENT_URI, "$inThreads AND ${Telephony.Mms.DATE} >= ${sinceMs / 1000}")
    }

    private fun exists(context: Context, uri: Uri, selection: String): Boolean =
        context.contentResolver.query(uri, arrayOf(BaseColumns._ID), selection, null, null)?.use { it.count > 0 } ?: false

    sealed class Decision {
        object Inbox : Decision()
        data class Spam(val reason: String, val percent: Int?) : Decision()
    }

    /**
     * Where does a new incoming SMS go? Blocking (it may call the API); call it from a background thread.
     * The order of the rules matters: "allowed" rules come before "blocked" rules.
     *  1. contact (setting, on by default)               -> inbox
     *  2. allowed number (also 091* prefixes) / allowed keyword /
     *     a conversation where I wrote to the sender, or with a
     *     message in the last N days (setting, 30; 0 = any) -> inbox
     *  3. looks like a login code (setting, on)          -> inbox
     *  4. blocked number / blocked keyword /
     *     "block unknown numbers" + not a contact        -> spam folder
     *  5. spam filter off or not set up                  -> inbox
     *  6. the API: spam -> spam folder; not spam, error, timeout (setting, 8-30 s), no internet -> inbox
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
            hasConversationWith(context, address) -> true
            config.allowedKeywords.any { it.isNotEmpty() && body.contains(it, ignoreCase = true) } -> true
            config.spamSkipCodes && looksLikeCode(body) -> true
            else -> false
        }
        if (isAllowed) return Decision.Inbox

        if (context.isNumberBlocked(address) || isBlockedByPrefix(context, address)) return Decision.Spam(REASON_BLOCKED_NUMBER, null)
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

    /** Blocking; waits at most the "API wait time" setting. Call it from a background thread. */
    fun check(context: Context, address: String, body: String): Result {
        val config = context.config
        val timeoutSecs = config.spamApiTimeoutSecs
        val timeoutMs = timeoutSecs * 1000
        val json = JSONObject().put("sender", address).put("body", body)
        val future = executor.submit<Result> {
            // connecting (DNS + TLS) can be slow on mobile data, so it gets the whole time too
            val (code, text) = post(config.spamApiUrl, config.spamApiToken, json, timeoutMs, timeoutMs)
            parseCheck(code, text)
        }
        return try {
            future.get(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            Result.Error("no answer in $timeoutSecs s")
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
            val (code, text) = post(feedbackUrl(config.spamApiUrl), config.spamApiToken, json, FEEDBACK_TIMEOUT_MS, FEEDBACK_TIMEOUT_MS)
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

    private fun post(url: String, token: String, json: JSONObject, connectTimeoutMs: Int, readTimeoutMs: Int): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
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

package org.fossify.messages.helpers

import androidx.annotation.StringRes
import org.fossify.messages.R

/**
 * sms-man: the editable text lists that share the "Manage blocked keywords" screen
 * (ManageBlockedKeywordsActivity, opened with the extra [KEYWORD_LIST] = the enum name).
 *
 * BLOCKED_KEYWORDS: body contains it -> spam folder (no API call)
 * ALLOWED_KEYWORDS: body contains it -> inbox (no API call)
 * ALLOWED_NUMBERS:  sender matches it -> inbox (no API call); "Not spam" adds the sender here
 */
enum class KeywordList(
    val prefKey: String,
    @StringRes val titleRes: Int,
    @StringRes val emptyRes: Int,
    @StringRes val addRes: Int,
    @StringRes val hintRes: Int,
    @StringRes val fileNameRes: Int,
) {
    BLOCKED_KEYWORDS(
        BLOCKED_KEYWORDS_KEY,
        R.string.manage_blocked_keywords,
        R.string.not_blocking_keywords,
        R.string.add_a_blocked_keyword,
        R.string.keyword,
        R.string.blocked_keywords
    ),
    ALLOWED_KEYWORDS(
        ALLOWED_KEYWORDS_KEY,
        R.string.manage_allowed_keywords,
        R.string.no_allowed_keywords,
        R.string.add_an_allowed_keyword,
        R.string.keyword,
        R.string.allowed_keywords
    ),
    ALLOWED_NUMBERS(
        ALLOWED_NUMBERS_KEY,
        R.string.manage_allowed_numbers,
        R.string.no_allowed_numbers,
        R.string.add_an_allowed_number,
        R.string.number_or_sender_name,
        R.string.allowed_numbers
    );

    companion object {
        fun fromName(name: String?) = entries.firstOrNull { it.name == name } ?: BLOCKED_KEYWORDS
    }
}

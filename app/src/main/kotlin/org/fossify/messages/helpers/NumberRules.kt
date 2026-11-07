package org.fossify.messages.helpers

import android.content.Context
import org.fossify.messages.R
import org.fossify.messages.extensions.config

/**
 * sms-man: rules for the "allowed numbers" and "blocked numbers" lists.
 * An entry is a number (09105000175, +989105000175), a sender name, or a prefix with * at the end (091*).
 * 0910..., and +98910... are the same number.
 */
object NumberRules {
    private const val PHONE_DIGITS = 10

    /** Same text for the same number: no spaces or dashes, lower case, "+98" / "0098" become "0". */
    fun canon(value: String): String {
        val v = value.filterNot { it == ' ' || it == '-' }.lowercase()
        return when {
            v.startsWith("+98") -> "0" + v.substring(3)
            v.startsWith("0098") -> "0" + v.substring(4)
            else -> v
        }
    }

    fun isPrefix(entry: String) = entry.trim().endsWith("*")

    private fun prefixOf(entry: String) = canon(entry.trim().removeSuffix("*"))

    private fun isPhone(v: String) = v.length >= PHONE_DIGITS && v.all { it.isDigit() }

    /** Does the number [sender] (or sender name) match [entry] (exact entry or prefix)? */
    fun matches(entry: String, sender: String): Boolean {
        val s = canon(sender)
        if (isPrefix(entry)) {
            val p = prefixOf(entry)
            return p.isNotEmpty() && s.startsWith(p)
        }
        val e = canon(entry)
        return e == s || (isPhone(e) && isPhone(s) && e.takeLast(PHONE_DIGITS) == s.takeLast(PHONE_DIGITS))
    }

    /** Do two entries cover a common number? (equal numbers, a prefix that covers a number, or nested prefixes) */
    fun overlaps(a: String, b: String): Boolean = when {
        isPrefix(a) && isPrefix(b) -> prefixOf(a).startsWith(prefixOf(b)) || prefixOf(b).startsWith(prefixOf(a))
        isPrefix(a) -> matches(a, b)
        isPrefix(b) -> matches(b, a)
        else -> matches(a, b)
    }

    /**
     * Error text if [value] cannot be added to [list], else null. Looks in the allowed AND the blocked list.
     * [original] is the entry being edited (it is ignored).
     */
    fun findConflict(context: Context, list: KeywordList, value: String, original: String? = null): String? {
        val v = value.trim()
        val star = v.indexOf('*')
        if (star != -1 && (star != v.length - 1 || v.length == 1)) {
            return context.getString(R.string.wildcard_only_end)
        }
        val config = context.config
        for (other in listOf(KeywordList.ALLOWED_NUMBERS, KeywordList.BLOCKED_NUMBERS)) {
            for (entry in config.getKeywordList(other)) {
                if (other == list && entry == original) continue
                if (!overlaps(v, entry)) continue
                return if (other == list) {
                    context.getString(R.string.conflict_same_list, v, entry)
                } else {
                    val name = context.getString(
                        if (other == KeywordList.ALLOWED_NUMBERS) R.string.list_allowed_numbers else R.string.list_blocked_numbers
                    )
                    context.getString(R.string.conflict_other_list, entry, name)
                }
            }
        }
        return null
    }
}

package org.fossify.messages.helpers

import android.app.Activity
import org.fossify.commons.extensions.showErrorToast
import org.fossify.messages.extensions.config

import java.io.File

class BlockedKeywordsImporter(
    private val activity: Activity,
    private val list: KeywordList = KeywordList.BLOCKED_KEYWORDS,
) {
    enum class ImportResult {
        IMPORT_FAIL, IMPORT_OK
    }

    fun importBlockedKeywords(path: String): ImportResult {
        return try {
            val inputStream = File(path).inputStream()
            val keywords = inputStream.bufferedReader().use {
                val content = it.readText().trimEnd().split(BLOCKED_KEYWORDS_EXPORT_DELIMITER)
                content
            }
            if (keywords.isNotEmpty()) {
                keywords.forEach { keyword: String ->
                    activity.config.addToKeywordList(list, keyword)
                }
                ImportResult.IMPORT_OK
            } else {
                ImportResult.IMPORT_FAIL
            }

        } catch (e: Exception) {
            activity.showErrorToast(e)
            ImportResult.IMPORT_FAIL
        }
    }
}

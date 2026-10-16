package org.fossify.messages.activities

import android.content.ActivityNotFoundException
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getTempFile
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.underlineText
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.ExportResult
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.interfaces.RefreshRecyclerViewListener
import org.fossify.messages.R
import org.fossify.messages.databinding.ActivityManageBlockedKeywordsBinding
import org.fossify.messages.dialogs.AddBlockedKeywordDialog
import org.fossify.messages.dialogs.ExportBlockedKeywordsDialog
import org.fossify.messages.dialogs.ManageBlockedKeywordsAdapter
import org.fossify.messages.extensions.config
import org.fossify.messages.extensions.toArrayList
import org.fossify.messages.helpers.BlockedKeywordsExporter
import org.fossify.messages.helpers.BlockedKeywordsImporter
import org.fossify.messages.helpers.KEYWORD_LIST
import org.fossify.messages.helpers.KeywordList
import java.io.FileOutputStream
import java.io.OutputStream

// sms-man: this screen also manages "allowed keywords" and "allowed numbers" (extra KEYWORD_LIST)
class ManageBlockedKeywordsActivity : SimpleActivity(), RefreshRecyclerViewListener {

    private val binding by viewBinding(ActivityManageBlockedKeywordsBinding::inflate)
    private val list by lazy { KeywordList.fromName(intent.getStringExtra(KEYWORD_LIST)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupListTexts()
        updateBlockedKeywords()
        setupOptionsMenu()

        setupEdgeToEdge(padBottomImeAndSystem = listOf(binding.manageBlockedKeywordsList))
        setupMaterialScrollListener(
            scrollingView = binding.manageBlockedKeywordsList,
            topAppBar = binding.blockKeywordsAppbar
        )
        updateTextColors(binding.manageBlockedKeywordsWrapper)

        binding.manageBlockedKeywordsPlaceholder2.apply {
            underlineText()
            setTextColor(getProperPrimaryColor())
            setOnClickListener {
                addOrEditBlockedKeyword()
            }
        }
    }

    private fun setupListTexts() {
        binding.blockKeywordsToolbar.title = getString(list.titleRes)
        binding.manageBlockedKeywordsPlaceholder.text = getString(list.emptyRes)
        binding.manageBlockedKeywordsPlaceholder2.text = getString(list.addRes)
        binding.blockKeywordsToolbar.menu.apply {
            findItem(R.id.add_blocked_keyword).title = getString(list.addRes)
            if (list != KeywordList.BLOCKED_KEYWORDS) {
                findItem(R.id.import_blocked_keywords).title = getString(R.string.import_from_file)
                findItem(R.id.export_blocked_keywords).title = getString(R.string.export_to_file)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.blockKeywordsAppbar, NavigationIcon.Arrow)
    }

    private fun setupOptionsMenu() {
        binding.blockKeywordsToolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.add_blocked_keyword -> {
                    addOrEditBlockedKeyword()
                    true
                }

                R.id.export_blocked_keywords -> {
                    tryExportBlockedNumbers()
                    true
                }

                R.id.import_blocked_keywords -> {
                    tryImportBlockedKeywords()
                    true
                }

                else -> false
            }
        }
    }

    private val createDocument =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            try {
                val outputStream = uri?.let { contentResolver.openOutputStream(it) }
                if (outputStream != null) {
                    exportBlockedKeywordsTo(outputStream)
                }
            } catch (e: Exception) {
                showErrorToast(e)
            }
        }

    private val getContent =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            try {
                if (uri != null) {
                    tryImportBlockedKeywordsFromFile(uri)
                }
            } catch (e: Exception) {
                showErrorToast(e)
            }
        }

    private fun tryImportBlockedKeywords() {
        val mimeType = "text/plain"
        try {
            getContent.launch(mimeType)
        } catch (_: ActivityNotFoundException) {
            toast(org.fossify.commons.R.string.system_service_disabled, Toast.LENGTH_LONG)
        } catch (e: Exception) {
            showErrorToast(e)
        }
    }

    private fun tryImportBlockedKeywordsFromFile(uri: Uri) {
        when (uri.scheme) {
            "file" -> importBlockedKeywords(uri.path!!)
            "content" -> {
                val tempFile = getTempFile("blocked", "blocked_keywords.txt")
                if (tempFile == null) {
                    toast(org.fossify.commons.R.string.unknown_error_occurred)
                    return
                }

                try {
                    val inputStream = contentResolver.openInputStream(uri)
                    val out = FileOutputStream(tempFile)
                    inputStream!!.copyTo(out)
                    importBlockedKeywords(tempFile.absolutePath)
                } catch (e: Exception) {
                    showErrorToast(e)
                }
            }

            else -> toast(org.fossify.commons.R.string.invalid_file_format)
        }
    }

    private fun importBlockedKeywords(path: String) {
        ensureBackgroundThread {
            val result = BlockedKeywordsImporter(this, list).importBlockedKeywords(path)
            toast(
                when (result) {
                    BlockedKeywordsImporter.ImportResult.IMPORT_OK -> org.fossify.commons.R.string.importing_successful
                    BlockedKeywordsImporter.ImportResult.IMPORT_FAIL -> org.fossify.commons.R.string.no_items_found
                }
            )
            updateBlockedKeywords()
        }
    }

    private fun exportBlockedKeywordsTo(outputStream: OutputStream?) {
        ensureBackgroundThread {
            val blockedKeywords = config.getKeywordList(list).toArrayList()
            if (blockedKeywords.isEmpty()) {
                toast(org.fossify.commons.R.string.no_entries_for_exporting)
            } else {
                BlockedKeywordsExporter.exportBlockedKeywords(blockedKeywords, outputStream) {
                    toast(
                        when (it) {
                            ExportResult.EXPORT_OK -> org.fossify.commons.R.string.exporting_successful
                            else -> org.fossify.commons.R.string.exporting_failed
                        }
                    )
                }
            }
        }
    }

    private fun tryExportBlockedNumbers() {
        ExportBlockedKeywordsDialog(
            activity = this,
            path = config.lastBlockedKeywordExportPath,
            hidePath = true,
            fileTitle = getString(list.fileNameRes)
        ) { file ->
            try {
                createDocument.launch(file.name)
            } catch (_: ActivityNotFoundException) {
                toast(
                    org.fossify.commons.R.string.system_service_disabled,
                    Toast.LENGTH_LONG
                )
            } catch (e: Exception) {
                showErrorToast(e)
            }
        }
    }

    override fun refreshItems() {
        updateBlockedKeywords()
    }

    private fun updateBlockedKeywords() {
        ensureBackgroundThread {
            val blockedKeywords = config.getKeywordList(list).sorted().toArrayList()
            runOnUiThread {
                ManageBlockedKeywordsAdapter(
                    activity = this,
                    blockedKeywords = blockedKeywords,
                    list = list,
                    listener = this,
                    recyclerView = binding.manageBlockedKeywordsList
                ) {
                    addOrEditBlockedKeyword(it as String)
                }.apply {
                    binding.manageBlockedKeywordsList.adapter = this
                }

                binding.manageBlockedKeywordsPlaceholder.beVisibleIf(blockedKeywords.isEmpty())
                binding.manageBlockedKeywordsPlaceholder2.beVisibleIf(blockedKeywords.isEmpty())
            }
        }
    }

    private fun addOrEditBlockedKeyword(keyword: String? = null) {
        AddBlockedKeywordDialog(this, keyword, list) {
            updateBlockedKeywords()
        }
    }
}

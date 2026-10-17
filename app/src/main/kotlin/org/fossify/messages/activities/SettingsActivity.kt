package org.fossify.messages.activities

import android.app.NotificationManager
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import org.fossify.commons.activities.ManageBlockedNumbersActivity
import org.fossify.commons.dialogs.ChangeDateTimeFormatDialog
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.dialogs.RadioGroupDialog
import org.fossify.commons.dialogs.SecurityDialog
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.formatWithDeprecatedBadge
import org.fossify.commons.extensions.getBlockedNumbers
import org.fossify.commons.extensions.getFontSizeText
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.notificationManager
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.FONT_SIZE_EXTRA_LARGE
import org.fossify.commons.helpers.FONT_SIZE_LARGE
import org.fossify.commons.helpers.FONT_SIZE_MEDIUM
import org.fossify.commons.helpers.FONT_SIZE_SMALL
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.PERMISSION_READ_CALL_LOG
import org.fossify.commons.helpers.PROTECTION_FINGERPRINT
import org.fossify.commons.helpers.SHOW_ALL_TABS
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.helpers.isQPlus
import org.fossify.commons.helpers.isRPlus
import org.fossify.commons.helpers.isSPlus
import org.fossify.commons.helpers.isTiramisuPlus
import org.fossify.commons.models.RadioItem
import org.fossify.messages.R
import org.fossify.messages.databinding.ActivitySettingsBinding
import org.fossify.messages.dialogs.ExportMessagesDialog
import org.fossify.messages.dialogs.SpamSettingDialog
import org.fossify.messages.extensions.config
import org.fossify.messages.extensions.countOldMessages
import org.fossify.messages.extensions.deleteOldMessages
import org.fossify.messages.extensions.emptyMessagesRecycleBin
import org.fossify.messages.extensions.messagesDB
import org.fossify.messages.extensions.notificationHelper
import org.fossify.messages.helpers.CallLogNotes
import org.fossify.messages.helpers.FILE_SIZE_100_KB
import org.fossify.messages.helpers.FILE_SIZE_1_MB
import org.fossify.messages.helpers.FILE_SIZE_200_KB
import org.fossify.messages.helpers.FILE_SIZE_2_MB
import org.fossify.messages.helpers.FILE_SIZE_300_KB
import org.fossify.messages.helpers.FILE_SIZE_600_KB
import org.fossify.messages.helpers.FILE_SIZE_NONE
import org.fossify.messages.helpers.KEYWORD_LIST
import org.fossify.messages.helpers.KeywordList
import org.fossify.messages.helpers.LOCK_SCREEN_NOTHING
import org.fossify.messages.helpers.LOCK_SCREEN_SENDER
import org.fossify.messages.helpers.LOCK_SCREEN_SENDER_MESSAGE
import org.fossify.messages.helpers.MessagesImporter
import org.fossify.messages.helpers.SpamFilter
import org.fossify.messages.helpers.TelegramAvatars
import org.fossify.messages.helpers.refreshConversations
import java.util.Locale
import kotlin.system.exitProcess

private const val MAX_AUTO_DELETE_DAYS = 36500
private val SPAM_API_TIMEOUT_CHOICES = listOf(8, 10, 15, 20, 25, 30) // sms-man: seconds

class SettingsActivity : SimpleActivity() {
    private var blockedNumbersAtPause = -1
    private var recycleBinMessages = 0
    private val messagesFileType = "application/json"
    private val messageImportFileTypes = buildList {
        add("application/json")
        add("application/xml")
        add("text/xml")
        if (!isQPlus()) {
            add("application/octet-stream")
        }
    }

    private val getContent =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                MessagesImporter(this).importMessages(uri)
            }
        }

    private var exportMessagesDialog: ExportMessagesDialog? = null

    private val saveDocument =
        registerForActivityResult(ActivityResultContracts.CreateDocument(messagesFileType)) { uri ->
            if (uri != null) {
                toast(org.fossify.commons.R.string.exporting)
                exportMessagesDialog?.exportMessages(uri)
            }
        }

    private val binding by viewBinding(ActivitySettingsBinding::inflate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        setupEdgeToEdge(padBottomImeAndSystem = listOf(binding.settingsNestedScrollview))
        setupMaterialScrollListener(
            scrollingView = binding.settingsNestedScrollview,
            topAppBar = binding.settingsAppbar
        )
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.settingsAppbar, NavigationIcon.Arrow)

        setupCustomizeColors()
        setupCustomizeNotifications()
        setupUseEnglish()
        setupLanguage()
        setupManageBlockedNumbers()
        setupManageBlockedKeywords()
        setupManageAllowedLists()
        setupChangeDateTimeFormat()
        setupFontSize()
        setupShowCharacterCounter()
        setupUseSimpleCharacters()
        setupSendOnEnter()
        setupEnableDeliveryReports()
        setupSendLongMessageAsMMS()
        setupGroupMessageAsMMS()
        setupSpamFilter()
        setupUnknownNumbers()
        setupKeepConversationsArchived()
        setupAutoDeleteOld()
        setupLockScreenVisibility()
        setupBubbleNotifications()
        setupMMSFileSizeLimit()
        setupUseRecycleBin()
        setupEmptyRecycleBin()
        setupAppPasswordProtection()
        setupMessagesExport()
        setupMessagesImport()
        updateTextColors(binding.settingsNestedScrollview)

        if (
            blockedNumbersAtPause != -1 && blockedNumbersAtPause != getBlockedNumbers().hashCode()
        ) {
            refreshConversations()
        }

        arrayOf(
            binding.settingsColorCustomizationSectionLabel,
            binding.settingsGeneralSettingsLabel,
            binding.settingsOutgoingMessagesLabel,
            binding.settingsNotificationsLabel,
            binding.settingsSpamLabel,
            binding.settingsUnknownNumbersLabel,
            binding.settingsArchivedMessagesLabel,
            binding.settingsRecycleBinLabel,
            binding.settingsOldMessagesLabel,
            binding.settingsSecurityLabel,
            binding.settingsMigratingLabel
        ).forEach {
            it.setTextColor(getProperPrimaryColor())
        }
    }

    private fun setupMessagesExport() {
        binding.settingsExportMessagesHolder.setOnClickListener {
            exportMessagesDialog = ExportMessagesDialog(this) { fileName ->
                saveDocument.launch("$fileName.json")
            }
        }
    }

    private fun setupMessagesImport() {
        binding.settingsImportMessagesHolder.setOnClickListener {
            getContent.launch(messageImportFileTypes.toTypedArray())
        }
    }

    override fun onPause() {
        super.onPause()
        blockedNumbersAtPause = getBlockedNumbers().hashCode()
    }

    private fun setupCustomizeColors() = binding.apply {
        settingsColorCustomizationHolder.setOnClickListener {
            startCustomizationActivity()
        }
    }

    private fun setupCustomizeNotifications() = binding.apply {
        settingsCustomizeNotificationsHolder.setOnClickListener {
            launchCustomizeNotificationsIntent()
        }
    }

    private fun setupUseEnglish() = binding.apply {
        settingsUseEnglishHolder.beVisibleIf(
            (config.wasUseEnglishToggled || Locale.getDefault().language != "en")
                    && !isTiramisuPlus()
        )
        settingsUseEnglish.isChecked = config.useEnglish
        settingsUseEnglishHolder.setOnClickListener {
            settingsUseEnglish.toggle()
            config.useEnglish = settingsUseEnglish.isChecked
            exitProcess(0)
        }
    }

    private fun setupLanguage() = binding.apply {
        settingsLanguage.text = Locale.getDefault().displayLanguage
        if (isTiramisuPlus()) {
            settingsLanguageHolder.beVisible()
            settingsLanguageHolder.setOnClickListener {
                launchChangeAppLanguageIntent()
            }
        } else {
            settingsLanguageHolder.beGone()
        }
    }

    private fun setupManageBlockedNumbers() = binding.apply {
        settingsManageBlockedNumbers.text =
            getString(org.fossify.commons.R.string.manage_blocked_numbers)
        settingsManageBlockedNumbersHolder.beVisible()
        settingsManageBlockedNumbersHolder.setOnClickListener {
            Intent(this@SettingsActivity, ManageBlockedNumbersActivity::class.java).apply {
                startActivity(this)
            }
        }
    }

    private fun setupManageBlockedKeywords() = binding.apply {
        settingsManageBlockedKeywords.text =
            getString(R.string.manage_blocked_keywords)

        settingsManageBlockedKeywordsHolder.setOnClickListener {
            Intent(this@SettingsActivity, ManageBlockedKeywordsActivity::class.java).apply {
                startActivity(this)
            }
        }
    }

    // sms-man: allowed numbers / keywords share the blocked keywords screen
    private fun setupManageAllowedLists() = binding.apply {
        settingsManageAllowedNumbersHolder.setOnClickListener { openKeywordList(KeywordList.ALLOWED_NUMBERS) }
        settingsManageAllowedKeywordsHolder.setOnClickListener { openKeywordList(KeywordList.ALLOWED_KEYWORDS) }
    }

    private fun openKeywordList(list: KeywordList) {
        Intent(this, ManageBlockedKeywordsActivity::class.java).apply {
            putExtra(KEYWORD_LIST, list.name)
            startActivity(this)
        }
    }

    private fun setupChangeDateTimeFormat() = binding.apply {
        settingsChangeDateTimeFormatHolder.setOnClickListener {
            ChangeDateTimeFormatDialog(this@SettingsActivity) {
                refreshConversations()
            }
        }
    }

    private fun setupFontSize() = binding.apply {
        settingsFontSize.text = getFontSizeText()
        settingsFontSizeHolder.setOnClickListener {
            val items = arrayListOf(
                RadioItem(FONT_SIZE_SMALL, getString(org.fossify.commons.R.string.small)),
                RadioItem(FONT_SIZE_MEDIUM, getString(org.fossify.commons.R.string.medium)),
                RadioItem(FONT_SIZE_LARGE, getString(org.fossify.commons.R.string.large)),
                RadioItem(
                    FONT_SIZE_EXTRA_LARGE,
                    getString(org.fossify.commons.R.string.extra_large)
                )
            )

            RadioGroupDialog(this@SettingsActivity, items, config.fontSize) {
                config.fontSize = it as Int
                settingsFontSize.text = getFontSizeText()
            }
        }
    }

    private fun setupShowCharacterCounter() = binding.apply {
        settingsShowCharacterCounter.isChecked = config.showCharacterCounter
        settingsShowCharacterCounterHolder.setOnClickListener {
            settingsShowCharacterCounter.toggle()
            config.showCharacterCounter = settingsShowCharacterCounter.isChecked
        }
    }

    private fun setupUseSimpleCharacters() = binding.apply {
        settingsUseSimpleCharacters.isChecked = config.useSimpleCharacters
        settingsUseSimpleCharactersHolder.setOnClickListener {
            settingsUseSimpleCharacters.toggle()
            config.useSimpleCharacters = settingsUseSimpleCharacters.isChecked
        }
    }

    private fun setupSendOnEnter() = binding.apply {
        settingsSendOnEnter.isChecked = config.sendOnEnter
        settingsSendOnEnterHolder.setOnClickListener {
            settingsSendOnEnter.toggle()
            config.sendOnEnter = settingsSendOnEnter.isChecked
        }
    }

    private fun setupEnableDeliveryReports() = binding.apply {
        settingsEnableDeliveryReports.isChecked = config.enableDeliveryReports
        settingsEnableDeliveryReportsHolder.setOnClickListener {
            settingsEnableDeliveryReports.toggle()
            config.enableDeliveryReports = settingsEnableDeliveryReports.isChecked
        }
    }

    private fun setupSendLongMessageAsMMS() = binding.apply {
        settingsSendLongMessageMms.isChecked = config.sendLongMessageMMS
        settingsSendLongMessageMmsHolder.setOnClickListener {
            settingsSendLongMessageMms.toggle()
            config.sendLongMessageMMS = settingsSendLongMessageMms.isChecked
        }
    }

    private fun setupGroupMessageAsMMS() = binding.apply {
        settingsSendGroupMessageMms.isChecked = config.sendGroupMessageMMS
        settingsSendGroupMessageMmsHolder.setOnClickListener {
            settingsSendGroupMessageMms.toggle()
            config.sendGroupMessageMMS = settingsSendGroupMessageMms.isChecked
        }
    }

    // sms-man: AI spam filter
    private fun setupSpamFilter() = binding.apply {
        settingsSpamFilterEnabled.isChecked = config.spamFilterEnabled
        settingsSpamFilterEnabledHolder.setOnClickListener {
            settingsSpamFilterEnabled.toggle()
            config.spamFilterEnabled = settingsSpamFilterEnabled.isChecked
        }

        settingsSpamSkipContacts.isChecked = config.spamSkipContacts
        settingsSpamSkipContactsHolder.setOnClickListener {
            settingsSpamSkipContacts.toggle()
            config.spamSkipContacts = settingsSpamSkipContacts.isChecked
        }

        settingsSpamSkipCodes.isChecked = config.spamSkipCodes
        settingsSpamSkipCodesHolder.setOnClickListener {
            settingsSpamSkipCodes.toggle()
            config.spamSkipCodes = settingsSpamSkipCodes.isChecked
        }

        updateSpamTexts()
        settingsSpamApiUrlHolder.setOnClickListener {
            SpamSettingDialog(
                activity = this@SettingsActivity,
                titleId = R.string.spam_api_url,
                value = config.spamApiUrl,
                hint = getString(R.string.spam_api_url_hint),
                isSecret = false,
                validate = { url ->
                    if (url.startsWith("https://", ignoreCase = true)) null
                    else getString(R.string.spam_url_must_be_https)
                }
            ) {
                config.spamApiUrl = it
                updateSpamTexts()
            }
        }

        settingsSpamApiTokenHolder.setOnClickListener {
            SpamSettingDialog(
                activity = this@SettingsActivity,
                titleId = R.string.spam_api_token,
                value = config.spamApiToken,
                hint = getString(R.string.spam_api_token),
                isSecret = true,
                validate = { null }
            ) {
                config.spamApiToken = it
                updateSpamTexts()
            }
        }

        settingsSpamTimeoutHolder.setOnClickListener {
            val items = SPAM_API_TIMEOUT_CHOICES.map {
                RadioItem(it, getString(R.string.seconds_count, it))
            }.toCollection(ArrayList())
            RadioGroupDialog(
                activity = this@SettingsActivity,
                items = items,
                checkedItemId = config.spamApiTimeoutSecs,
                titleId = R.string.spam_api_timeout
            ) {
                config.spamApiTimeoutSecs = it as Int
                updateSpamTexts()
            }
        }

        settingsSpamTestHolder.setOnClickListener { testSpamApi() }

    }

    private fun updateSpamTexts() = binding.apply {
        settingsSpamApiUrl.text = config.spamApiUrl.ifEmpty { getString(R.string.spam_not_set) }
        val token = config.spamApiToken
        settingsSpamApiToken.text =
            if (token.isEmpty()) getString(R.string.spam_not_set)
            else getString(R.string.spam_token_set, token.takeLast(4))
        settingsSpamTimeout.text = getString(R.string.spam_api_timeout_value, config.spamApiTimeoutSecs)
        settingsSpamTest.text = ""
        settingsSpamTest.beGone()
    }

    private fun testSpamApi() {
        if (config.spamApiUrl.isEmpty() || config.spamApiToken.isEmpty()) {
            toast(R.string.spam_filter_not_configured)
            return
        }
        binding.settingsSpamTest.beVisible()
        binding.settingsSpamTest.text = getString(R.string.spam_testing)
        ensureBackgroundThread {
            val start = System.currentTimeMillis()
            val result = SpamFilter.check(this, "", "sms-man connection test")
            val secs = String.format(Locale.US, "%.1f", (System.currentTimeMillis() - start) / 1000.0)
            val text = when (result) {
                is SpamFilter.Result.Spam -> getString(R.string.spam_test_ok, "true", secs)
                is SpamFilter.Result.NotSpam -> getString(R.string.spam_test_ok, "false", secs)
                is SpamFilter.Result.Error -> getString(R.string.spam_test_failed, result.message, secs)
            }
            runOnUiThread { binding.settingsSpamTest.text = text }
        }
    }

    // sms-man: icons for numbers that are not contacts (call log notes, Telegram pictures)
    private fun setupUnknownNumbers() = binding.apply {
        settingsCallLogNotes.isChecked = config.callLogNotesEnabled && CallLogNotes.hasPermission(this@SettingsActivity)
        settingsCallLogNotesHolder.setOnClickListener {
            if (settingsCallLogNotes.isChecked) {
                settingsCallLogNotes.isChecked = false
                config.callLogNotesEnabled = false
                CallLogNotes.reloadInBackground(this@SettingsActivity, force = true)
            } else {
                handlePermission(PERMISSION_READ_CALL_LOG) { granted ->
                    settingsCallLogNotes.isChecked = granted
                    config.callLogNotesEnabled = granted
                    if (granted) {
                        CallLogNotes.reloadInBackground(this@SettingsActivity, force = true)
                    } else {
                        toast(R.string.call_log_notes_no_permission)
                    }
                }
            }
        }

        settingsTelegramAvatars.isChecked = config.telegramAvatarsEnabled
        settingsTelegramAvatarsHolder.setOnClickListener {
            settingsTelegramAvatars.toggle()
            config.telegramAvatarsEnabled = settingsTelegramAvatars.isChecked
            refreshConversations()
        }

        updateTelegramTexts()
        settingsTelegramApiUrlHolder.setOnClickListener {
            SpamSettingDialog(
                activity = this@SettingsActivity,
                titleId = R.string.telegram_api_url,
                value = config.telegramApiUrl,
                hint = getString(R.string.telegram_api_url_hint),
                isSecret = false,
                validate = { url ->
                    if (url.startsWith("https://", ignoreCase = true)) null
                    else getString(R.string.spam_url_must_be_https)
                }
            ) {
                if (it.trim().trimEnd('/') != config.telegramApiUrl) TelegramAvatars.clearCache(this@SettingsActivity)
                config.telegramApiUrl = it
                updateTelegramTexts()
            }
        }

        settingsTelegramApiTokenHolder.setOnClickListener {
            SpamSettingDialog(
                activity = this@SettingsActivity,
                titleId = R.string.telegram_api_token,
                value = config.telegramApiToken,
                hint = getString(R.string.telegram_api_token),
                isSecret = true,
                validate = { null }
            ) {
                config.telegramApiToken = it
                updateTelegramTexts()
            }
        }

        settingsTelegramPrefixesHolder.setOnClickListener {
            SpamSettingDialog(
                activity = this@SettingsActivity,
                titleId = R.string.telegram_prefixes,
                value = config.telegramPrefixes,
                hint = getString(R.string.telegram_prefixes_hint),
                isSecret = false,
                validate = { value ->
                    if (value.any { it.isDigit() }) null else getString(R.string.telegram_prefixes_invalid)
                }
            ) {
                config.telegramPrefixes = it
                updateTelegramTexts()
            }
        }

        settingsTelegramTestHolder.setOnClickListener { testTelegramApi() }
    }

    private fun updateTelegramTexts() = binding.apply {
        settingsTelegramApiUrl.text = config.telegramApiUrl.ifEmpty { getString(R.string.spam_not_set) }
        val token = config.telegramApiToken
        settingsTelegramApiToken.text =
            if (token.isEmpty()) getString(R.string.spam_not_set)
            else getString(R.string.spam_token_set, token.takeLast(4))
        settingsTelegramPrefixes.text = config.telegramPrefixes.ifEmpty { getString(R.string.spam_not_set) }
        settingsTelegramTest.text = ""
        settingsTelegramTest.beGone()
    }

    private fun testTelegramApi() {
        if (config.telegramApiUrl.isEmpty() || config.telegramApiToken.isEmpty()) {
            toast(R.string.spam_filter_not_configured)
            return
        }
        binding.settingsTelegramTest.beVisible()
        binding.settingsTelegramTest.text = getString(R.string.spam_testing)
        ensureBackgroundThread {
            val (ok, message) = TelegramAvatars.test(this)
            val text = if (ok) getString(R.string.telegram_test_ok, message) else getString(R.string.telegram_test_failed, message)
            runOnUiThread { binding.settingsTelegramTest.text = text }
        }
    }

    // sms-man: delete old messages
    private fun setupAutoDeleteOld() = binding.apply {
        settingsAutoDeleteOld.isChecked = config.autoDeleteOldEnabled
        settingsAutoDeleteOldDays.text = getString(R.string.days_count, config.autoDeleteOldDays)

        settingsAutoDeleteOldHolder.setOnClickListener {
            settingsAutoDeleteOld.toggle()
            config.autoDeleteOldEnabled = settingsAutoDeleteOld.isChecked
            if (config.autoDeleteOldEnabled) askDeleteOldMessagesNow()
        }

        settingsAutoDeleteOldDaysHolder.setOnClickListener {
            SpamSettingDialog(
                activity = this@SettingsActivity,
                titleId = R.string.delete_messages_older_than,
                value = config.autoDeleteOldDays.toString(),
                hint = getString(R.string.days),
                isSecret = false,
                isNumber = true,
                validate = { value ->
                    if (value.toIntOrNull() in 1..MAX_AUTO_DELETE_DAYS) null
                    else getString(R.string.auto_delete_days_invalid)
                }
            ) { value ->
                val days = value.toIntOrNull() ?: return@SpamSettingDialog
                config.autoDeleteOldDays = days
                settingsAutoDeleteOldDays.text = getString(R.string.days_count, days)
                if (config.autoDeleteOldEnabled) askDeleteOldMessagesNow()
            }
        }
    }

    /**
     * The option was turned on or the days changed: ask before the first delete.
     * "No" keeps the messages for now, but the daily run deletes them in about 24 hours (the user is told).
     */
    private fun askDeleteOldMessagesNow() {
        val days = config.autoDeleteOldDays
        toast(R.string.auto_delete_checking)
        ensureBackgroundThread {
            val count = countOldMessages(days)
            runOnUiThread {
                if (count == 0) {
                    config.lastAutoDeleteOld = System.currentTimeMillis()
                    toast(getString(R.string.auto_delete_nothing, days))
                    return@runOnUiThread
                }
                showMessageDialog(
                    message = getString(R.string.auto_delete_confirm, count, days),
                    positiveId = org.fossify.commons.R.string.delete,
                    negativeId = org.fossify.commons.R.string.cancel,
                    onNegative = {
                        config.lastAutoDeleteOld = System.currentTimeMillis()
                        showMessageDialog(getString(R.string.auto_delete_postponed, days))
                    }
                ) {
                    toast(R.string.auto_delete_deleting)
                    ensureBackgroundThread {
                        val deleted = deleteOldMessages(days)
                        refreshConversations()
                        toast(getString(R.string.auto_delete_done, deleted))
                    }
                }
            }
        }
    }

    private fun showMessageDialog(
        message: String,
        positiveId: Int = org.fossify.commons.R.string.ok,
        negativeId: Int = 0,
        onNegative: () -> Unit = {},
        onPositive: () -> Unit = {},
    ) {
        val view = TextView(this).apply {
            val padding = resources.getDimensionPixelSize(org.fossify.commons.R.dimen.big_margin)
            setPadding(padding, padding, padding, 0)
            setTextColor(getProperTextColor())
            text = message
        }
        getAlertDialogBuilder()
            .setPositiveButton(positiveId) { _, _ -> onPositive() }
            .apply { if (negativeId != 0) setNegativeButton(negativeId) { _, _ -> onNegative() } }
            .apply { setupDialogStuff(view, this, cancelOnTouchOutside = false) }
    }

    private fun setupKeepConversationsArchived() = binding.apply {
        settingsKeepConversationsArchived.isChecked = config.keepConversationsArchived
        settingsKeepConversationsArchivedHolder.setOnClickListener {
            settingsKeepConversationsArchived.toggle()
            config.keepConversationsArchived = settingsKeepConversationsArchived.isChecked
        }
    }

    // sms-man: floating chat bubbles (Android 11+)
    private fun setupBubbleNotifications() = binding.apply {
        settingsBubbleNotificationsHolder.beVisibleIf(isRPlus())
        settingsBubbleNotifications.isChecked = config.bubbleNotifications
        settingsBubbleNotificationsHolder.setOnClickListener {
            settingsBubbleNotifications.toggle()
            config.bubbleNotifications = settingsBubbleNotifications.isChecked
            if (!config.bubbleNotifications) {
                notificationHelper.removeBubbles()
            } else if (!areBubblesAllowed()) {
                showMessageDialog(
                    message = getString(R.string.bubbles_not_allowed),
                    positiveId = R.string.open_settings,
                    negativeId = org.fossify.commons.R.string.cancel,
                ) {
                    try {
                        Intent(Settings.ACTION_APP_NOTIFICATION_BUBBLE_SETTINGS).apply {
                            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                            startActivity(this)
                        }
                    } catch (e: Exception) {
                        toast(org.fossify.commons.R.string.no_app_found)
                    }
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun areBubblesAllowed(): Boolean {
        return if (isSPlus()) {
            notificationManager.bubblePreference != NotificationManager.BUBBLE_PREFERENCE_NONE
        } else {
            notificationManager.areBubblesAllowed()
        }
    }

    private fun setupLockScreenVisibility() = binding.apply {
        settingsLockScreenVisibility.text = getLockScreenVisibilityText()
        settingsLockScreenVisibilityHolder.setOnClickListener {
            val items = arrayListOf(
                RadioItem(LOCK_SCREEN_SENDER_MESSAGE, getString(R.string.sender_and_message)),
                RadioItem(LOCK_SCREEN_SENDER, getString(R.string.sender_only)),
                RadioItem(LOCK_SCREEN_NOTHING, getString(org.fossify.commons.R.string.nothing)),
            )

            RadioGroupDialog(this@SettingsActivity, items, config.lockScreenVisibilitySetting) {
                config.lockScreenVisibilitySetting = it as Int
                if (it != LOCK_SCREEN_SENDER_MESSAGE) notificationHelper.removeBubbles() // sms-man
                settingsLockScreenVisibility.text = getLockScreenVisibilityText()
            }
        }
    }

    private fun getLockScreenVisibilityText() = getString(
        when (config.lockScreenVisibilitySetting) {
            LOCK_SCREEN_SENDER_MESSAGE -> R.string.sender_and_message
            LOCK_SCREEN_SENDER -> R.string.sender_only
            else -> org.fossify.commons.R.string.nothing
        }
    )

    private fun setupMMSFileSizeLimit() = binding.apply {
        settingsMmsFileSizeLimit.text = getMMSFileLimitText()
        settingsMmsFileSizeLimitHolder.setOnClickListener {
            val items = arrayListOf(
                RadioItem(7, getString(R.string.mms_file_size_limit_none), FILE_SIZE_NONE),
                RadioItem(6, getString(R.string.mms_file_size_limit_2mb), FILE_SIZE_2_MB),
                RadioItem(5, getString(R.string.mms_file_size_limit_1mb), FILE_SIZE_1_MB),
                RadioItem(4, getString(R.string.mms_file_size_limit_600kb), FILE_SIZE_600_KB),
                RadioItem(3, getString(R.string.mms_file_size_limit_300kb), FILE_SIZE_300_KB),
                RadioItem(2, getString(R.string.mms_file_size_limit_200kb), FILE_SIZE_200_KB),
                RadioItem(1, getString(R.string.mms_file_size_limit_100kb), FILE_SIZE_100_KB),
            )

            val checkedItemId = items.find { it.value == config.mmsFileSizeLimit }?.id ?: 7
            RadioGroupDialog(this@SettingsActivity, items, checkedItemId) {
                config.mmsFileSizeLimit = it as Long
                settingsMmsFileSizeLimit.text = getMMSFileLimitText()
            }
        }
    }

    private fun setupUseRecycleBin() = binding.apply {
        updateRecycleBinButtons()
        settingsUseRecycleBin.isChecked = config.useRecycleBin
        settingsUseRecycleBin.text = formatWithDeprecatedBadge(
            labelRes = org.fossify.commons.R.string.move_items_into_recycle_bin
        )
        settingsUseRecycleBinHolder.setOnClickListener {
            settingsUseRecycleBin.toggle()
            config.useRecycleBin = settingsUseRecycleBin.isChecked
            updateRecycleBinButtons()
        }
    }

    private fun updateRecycleBinButtons() = binding.apply {
        settingsEmptyRecycleBinHolder.beVisibleIf(config.useRecycleBin)
    }

    private fun setupEmptyRecycleBin() = binding.apply {
        ensureBackgroundThread {
            recycleBinMessages = messagesDB.getArchivedCount()
            runOnUiThread {
                settingsEmptyRecycleBinSize.text =
                    resources.getQuantityString(
                        R.plurals.delete_messages,
                        recycleBinMessages,
                        recycleBinMessages
                    )
            }
        }

        settingsEmptyRecycleBinHolder.setOnClickListener {
            if (recycleBinMessages == 0) {
                toast(org.fossify.commons.R.string.recycle_bin_empty)
            } else {
                ConfirmationDialog(
                    activity = this@SettingsActivity,
                    message = "",
                    messageId = R.string.empty_recycle_bin_messages_confirmation,
                    positive = org.fossify.commons.R.string.yes,
                    negative = org.fossify.commons.R.string.no
                ) {
                    ensureBackgroundThread {
                        emptyMessagesRecycleBin()
                    }
                    recycleBinMessages = 0
                    settingsEmptyRecycleBinSize.text =
                        resources.getQuantityString(
                            R.plurals.delete_messages,
                            recycleBinMessages,
                            recycleBinMessages
                        )
                }
            }
        }
    }

    private fun setupAppPasswordProtection() = binding.apply {
        settingsAppPasswordProtection.isChecked = config.isAppPasswordProtectionOn
        settingsAppPasswordProtectionHolder.setOnClickListener {
            val tabToShow = if (config.isAppPasswordProtectionOn) {
                config.appProtectionType
            } else {
                SHOW_ALL_TABS
            }

            SecurityDialog(
                activity = this@SettingsActivity,
                requiredHash = config.appPasswordHash,
                showTabIndex = tabToShow
            ) { hash, type, success ->
                if (success) {
                    val hasPasswordProtection = config.isAppPasswordProtectionOn
                    settingsAppPasswordProtection.isChecked = !hasPasswordProtection
                    config.isAppPasswordProtectionOn = !hasPasswordProtection
                    config.appPasswordHash = if (hasPasswordProtection) "" else hash
                    config.appProtectionType = type

                    if (config.isAppPasswordProtectionOn) {
                        val confirmationTextId =
                            if (config.appProtectionType == PROTECTION_FINGERPRINT) {
                                org.fossify.commons.R.string.fingerprint_setup_successfully
                            } else {
                                org.fossify.commons.R.string.protection_setup_successfully
                            }

                        ConfirmationDialog(
                            activity = this@SettingsActivity,
                            message = "",
                            messageId = confirmationTextId,
                            positive = org.fossify.commons.R.string.ok,
                            negative = 0
                        ) { }
                    }
                }
            }
        }
    }

    private fun getMMSFileLimitText() = getString(
        when (config.mmsFileSizeLimit) {
            FILE_SIZE_100_KB -> R.string.mms_file_size_limit_100kb
            FILE_SIZE_200_KB -> R.string.mms_file_size_limit_200kb
            FILE_SIZE_300_KB -> R.string.mms_file_size_limit_300kb
            FILE_SIZE_600_KB -> R.string.mms_file_size_limit_600kb
            FILE_SIZE_1_MB -> R.string.mms_file_size_limit_1mb
            FILE_SIZE_2_MB -> R.string.mms_file_size_limit_2mb
            else -> R.string.mms_file_size_limit_none
        }
    )
}

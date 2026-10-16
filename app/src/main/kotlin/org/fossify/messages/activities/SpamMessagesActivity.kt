package org.fossify.messages.activities

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.formatDate
import org.fossify.commons.extensions.formatDateOrTime
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.messages.R
import org.fossify.messages.databinding.ActivitySpamMessagesBinding
import org.fossify.messages.databinding.ItemSpamMessageBinding
import org.fossify.messages.extensions.restoreSpamMessage
import org.fossify.messages.extensions.spamMessagesDB
import org.fossify.messages.extensions.subscriptionManagerCompat
import org.fossify.messages.helpers.SpamFilter
import org.fossify.messages.helpers.refreshConversations
import org.fossify.messages.models.SpamMessage

/** sms-man: the spam folder. Tap a message to read it, then "Not spam" or delete it. */
class SpamMessagesActivity : SimpleActivity() {
    private val binding by viewBinding(ActivitySpamMessagesBinding::inflate)
    private val adapter = SpamAdapter()
    private var simSlots = mapOf<Int, Int>() // subscription id -> SIM slot number (1, 2)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        binding.spamList.adapter = adapter
        setupOptionsMenu()

        setupEdgeToEdge(padBottomImeAndSystem = listOf(binding.spamList))
        setupMaterialScrollListener(
            scrollingView = binding.spamList,
            topAppBar = binding.spamAppbar
        )
        simSlots = try {
            subscriptionManagerCompat().activeSubscriptionInfoList.orEmpty()
                .associate { it.subscriptionId to it.simSlotIndex + 1 }
        } catch (_: SecurityException) {
            emptyMap()
        }
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.spamAppbar, NavigationIcon.Arrow)
        updateTextColors(binding.spamWrapper)
        loadMessages()
    }

    private fun setupOptionsMenu() {
        binding.spamToolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.delete_all_spam -> {
                    askDeleteAll()
                    true
                }

                else -> false
            }
        }
    }

    private fun loadMessages() {
        ensureBackgroundThread {
            val messages = spamMessagesDB.getAll()
            runOnUiThread {
                adapter.setItems(messages)
                binding.spamPlaceholder.beVisibleIf(messages.isEmpty())
                binding.spamToolbar.menu.findItem(R.id.delete_all_spam).isVisible = messages.isNotEmpty()
            }
        }
    }

    private fun askDeleteAll() {
        ConfirmationDialog(this, getString(R.string.delete_all_spam_confirmation)) {
            ensureBackgroundThread {
                spamMessagesDB.deleteAll()
                loadMessages()
            }
        }
    }

    private fun showMessage(message: SpamMessage) {
        val view = TextView(this).apply {
            val padding = resources.getDimensionPixelSize(org.fossify.commons.R.dimen.big_margin)
            setPadding(padding, padding / 2, padding, 0)
            setTextColor(getProperTextColor())
            setTextIsSelectable(true)
            text = buildString {
                append(message.body)
                append("\n\n")
                append(message.date.formatDate(this@SpamMessagesActivity))
                append("\n")
                append(infoText(message))
            }
        }

        getAlertDialogBuilder()
            .setPositiveButton(R.string.not_spam) { _, _ -> markNotSpam(message) }
            .setNeutralButton(org.fossify.commons.R.string.delete) { _, _ -> delete(message) }
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .apply { setupDialogStuff(view, this, titleText = message.address) }
    }

    private fun markNotSpam(message: SpamMessage) {
        ensureBackgroundThread {
            try {
                restoreSpamMessage(message)
            } catch (e: Exception) {
                showErrorToast(e)
                return@ensureBackgroundThread
            }
            refreshConversations()
            toast(R.string.moved_to_inbox)
            loadMessages()
            SpamFilter.sendFeedback(this, message.address, message.body, spam = false)?.let {
                toast(getString(R.string.spam_feedback_failed, it))
            }
        }
    }

    private fun delete(message: SpamMessage) {
        ensureBackgroundThread {
            spamMessagesDB.delete(message.id)
            loadMessages()
        }
    }

    private fun infoText(message: SpamMessage): String {
        val reason = when {
            message.reason == "sender" -> getString(R.string.spam_reason_sender)
            message.reason == "phrase" -> getString(R.string.spam_reason_phrase)
            message.reason == "ai" ->
                if (message.percent != null) getString(R.string.spam_reason_ai, message.percent) else "AI"
            message.reason == SpamFilter.REASON_REPORT -> getString(R.string.spam_reason_report)
            message.reason == SpamFilter.REASON_BLOCKED_NUMBER -> getString(R.string.spam_reason_blocked_number)
            message.reason == SpamFilter.REASON_UNKNOWN_NUMBER -> getString(R.string.spam_reason_unknown_number)
            message.reason.startsWith(SpamFilter.REASON_BLOCKED_KEYWORD_PREFIX) -> getString(
                R.string.spam_reason_blocked_keyword,
                message.reason.removePrefix(SpamFilter.REASON_BLOCKED_KEYWORD_PREFIX)
            )
            else -> message.reason
        }
        val slot = simSlots[message.subscriptionId]
        return if (slot != null) "SIM $slot · $reason" else reason
    }

    private inner class SpamAdapter : RecyclerView.Adapter<SpamAdapter.Holder>() {
        private var items = listOf<SpamMessage>()

        inner class Holder(val binding: ItemSpamMessageBinding) : RecyclerView.ViewHolder(binding.root)

        @Suppress("NotifyDataSetChanged")
        fun setItems(newItems: List<SpamMessage>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemSpamMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val message = items[position]
            val textColor = getProperTextColor()
            holder.binding.apply {
                spamItemSender.text = message.address
                spamItemDate.text = message.date.formatDateOrTime(
                    context = this@SpamMessagesActivity,
                    hideTimeOnOtherDays = true,
                    showCurrentYear = false
                )
                spamItemInfo.text = infoText(message)
                spamItemBody.text = message.body
                arrayOf(spamItemSender, spamItemDate, spamItemInfo, spamItemBody).forEach { it.setTextColor(textColor) }
                spamItemHolder.setOnClickListener { showMessage(message) }
            }
        }
    }
}

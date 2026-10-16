package org.fossify.messages.dialogs

import android.app.Activity
import android.content.DialogInterface.BUTTON_POSITIVE
import android.text.InputType
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.extensions.showKeyboard
import org.fossify.messages.databinding.DialogSpamSettingBinding

/**
 * sms-man: edit one text setting (spam API URL or token, days for "delete old messages").
 * [validate] returns an error message, or null when the value is OK. An empty value is allowed (= not set).
 */
class SpamSettingDialog(
    activity: Activity,
    titleId: Int,
    value: String,
    hint: String,
    isSecret: Boolean,
    isNumber: Boolean = false,
    validate: (String) -> String?,
    callback: (String) -> Unit,
) {
    init {
        val binding = DialogSpamSettingBinding.inflate(activity.layoutInflater).apply {
            spamSettingInputLayout.hint = hint
            spamSettingEditText.setText(value)
            if (isNumber) {
                spamSettingEditText.inputType = InputType.TYPE_CLASS_NUMBER
            } else if (isSecret) {
                spamSettingEditText.inputType =
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            }
        }

        activity.getAlertDialogBuilder()
            .setPositiveButton(org.fossify.commons.R.string.ok, null)
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(binding.root, this, titleId) { alertDialog ->
                    alertDialog.showKeyboard(binding.spamSettingEditText)
                    alertDialog.getButton(BUTTON_POSITIVE).setOnClickListener {
                        val newValue = binding.spamSettingEditText.text.toString().trim()
                        val error = if (newValue.isEmpty()) null else validate(newValue)
                        if (error != null) {
                            binding.spamSettingInputLayout.error = error
                            return@setOnClickListener
                        }
                        callback(newValue)
                        alertDialog.dismiss()
                    }
                }
            }
    }
}

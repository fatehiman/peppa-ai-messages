package org.fossify.messages.models

import androidx.room.ColumnInfo

/** sms-man: thread id and phone number(s) of a conversation ("a,b" for a group). */
data class ThreadNumber(
    @ColumnInfo(name = "thread_id") val threadId: Long,
    @ColumnInfo(name = "phone_number") val phoneNumber: String,
)

package org.fossify.messages.models

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * An incoming SMS that the spam filter caught. It lives only in the app's own database,
 * never in the system SMS database, so other SMS apps never see it.
 * "Not spam" moves it into the system database (the normal inbox).
 */
@Entity(tableName = "spam_messages")
data class SpamMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "address") val address: String,
    @ColumnInfo(name = "body") val body: String,
    @ColumnInfo(name = "date") val date: Long, // milliseconds
    @ColumnInfo(name = "subscription_id") val subscriptionId: Int,
    @ColumnInfo(name = "reason") val reason: String, // sender | phrase | ai | report
    @ColumnInfo(name = "percent") val percent: Int?,
)

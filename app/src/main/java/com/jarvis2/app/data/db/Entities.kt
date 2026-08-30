package com.jarvis2.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: String, // USER | ASSISTANT | SYSTEM — mirrors ai.Turn.Role
    val text: String,
    val timestamp: Long,
)

/**
 * Local "memory" rows consumed by [com.jarvis2.app.ai.MemoryStore] for
 * TF-IDF retrieval. `source` is free text like "chat", "vault:Projet X.md",
 * "calendar" — kept simple on purpose so any part of the app can write a
 * memory without a schema migration.
 */
@Entity(tableName = "memory")
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val source: String,
    val timestamp: Long,
)

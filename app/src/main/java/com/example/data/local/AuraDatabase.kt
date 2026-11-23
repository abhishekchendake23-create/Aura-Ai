package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        PersonaEntity::class,
        MemoryEntity::class,
        KnowledgeDocEntity::class,
        KnowledgeChunkEntity::class,
        AutopilotTaskEntity::class,
        ProjectEntity::class,
        ArtifactEntity::class,
        PromptTemplateEntity::class,
        CustomWebhookEntity::class
    ],
    version = 7,
    exportSchema = false
)
abstract class AuraDatabase : RoomDatabase() {
    abstract fun auraDao(): AuraDao

    companion object {
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN sourcesJson TEXT")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `memories` (
                        `id` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        `category` TEXT NOT NULL,
                        `isEnabled` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `knowledge_docs` (
                        `id` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `sourceType` TEXT NOT NULL,
                        `chunkCount` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `knowledge_chunks` (
                        `id` TEXT NOT NULL,
                        `docId` TEXT NOT NULL,
                        `docTitle` TEXT NOT NULL,
                        `chunkIndex` INTEGER NOT NULL,
                        `content` TEXT NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`docId`) REFERENCES `knowledge_docs`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_knowledge_chunks_docId` ON `knowledge_chunks` (`docId`)"
                )
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN isPinned INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN variantsJson TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN selectedVariantIndex INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `autopilot_tasks` (
                        `id` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `prompt` TEXT NOT NULL,
                        `intervalHours` INTEGER NOT NULL DEFAULT 24,
                        `useWebSearch` INTEGER NOT NULL DEFAULT 1,
                        `isEnabled` INTEGER NOT NULL DEFAULT 1,
                        `lastRunAt` INTEGER NOT NULL DEFAULT 0,
                        `lastResultSummary` TEXT NOT NULL DEFAULT '',
                        `lastConversationId` TEXT,
                        `createdAt` INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversations ADD COLUMN projectId TEXT")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `projects` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `description` TEXT NOT NULL DEFAULT '',
                        `customInstructions` TEXT NOT NULL DEFAULT '',
                        `pinnedUrlsOrNotes` TEXT NOT NULL DEFAULT '',
                        `accentHex` TEXT NOT NULL DEFAULT '#7AA2FF',
                        `createdAt` INTEGER NOT NULL DEFAULT 0,
                        `updatedAt` INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `artifacts` (
                        `id` TEXT NOT NULL,
                        `conversationId` TEXT NOT NULL,
                        `messageId` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `language` TEXT NOT NULL,
                        `code` TEXT NOT NULL,
                        `versionNumber` INTEGER NOT NULL DEFAULT 1,
                        `createdAt` INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_artifacts_conversationId` ON `artifacts` (`conversationId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_artifacts_messageId` ON `artifacts` (`messageId`)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `prompt_templates` (
                        `id` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `category` TEXT NOT NULL DEFAULT 'General',
                        `systemPrompt` TEXT NOT NULL DEFAULT '',
                        `userTemplate` TEXT NOT NULL,
                        `isBuiltIn` INTEGER NOT NULL DEFAULT 0,
                        `createdAt` INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `custom_webhooks` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `description` TEXT NOT NULL DEFAULT '',
                        `endpointUrl` TEXT NOT NULL,
                        `httpMethod` TEXT NOT NULL DEFAULT 'GET',
                        `headersJson` TEXT NOT NULL DEFAULT '',
                        `bodyTemplate` TEXT NOT NULL DEFAULT '',
                        `isEnabled` INTEGER NOT NULL DEFAULT 1,
                        `createdAt` INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
            }
        }

        @Volatile
        private var INSTANCE: AuraDatabase? = null

        fun getInstance(context: Context): AuraDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AuraDatabase::class.java,
                    "aura_workspace.db"
                )
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}

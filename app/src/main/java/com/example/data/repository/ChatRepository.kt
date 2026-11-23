package com.example.data.repository

import com.example.data.local.ArtifactEntity
import com.example.data.local.AuraDao
import com.example.data.local.AutopilotTaskEntity
import com.example.data.local.ConversationEntity
import com.example.data.local.CustomWebhookEntity
import com.example.data.local.KnowledgeChunkEntity
import com.example.data.local.KnowledgeDocEntity
import com.example.data.local.MemoryEntity
import com.example.data.local.MessageEntity
import com.example.data.local.MessageSearchHit
import com.example.data.local.PersonaEntity
import com.example.data.local.ProjectEntity
import com.example.data.local.PromptTemplateEntity
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class ChatRepository(private val dao: AuraDao) {

    fun observeConversations(): Flow<List<ConversationEntity>> = dao.observeConversations()

    fun observeMessages(conversationId: String): Flow<List<MessageEntity>> =
        dao.observeMessages(conversationId)

    fun observeBookmarkedMessages(): Flow<List<MessageEntity>> = dao.observeBookmarkedMessages()

    fun observePersonas(): Flow<List<PersonaEntity>> = dao.observePersonas()

    fun observeMemories(): Flow<List<MemoryEntity>> = dao.observeMemories()

    fun observeKnowledgeDocs(): Flow<List<KnowledgeDocEntity>> = dao.observeKnowledgeDocs()

    fun observeProjects(): Flow<List<ProjectEntity>> = dao.observeProjects()

    fun observeArtifacts(): Flow<List<ArtifactEntity>> = dao.observeArtifacts()

    fun observePromptTemplates(): Flow<List<PromptTemplateEntity>> = dao.observePromptTemplates()

    fun observeCustomWebhooks(): Flow<List<CustomWebhookEntity>> = dao.observeCustomWebhooks()

    suspend fun ensureDefaultPersonas() {
        dao.upsertPersonas(defaultPersonas())
    }

    suspend fun ensureDefaultPromptTemplates() {
        dao.upsertPromptTemplates(defaultPromptTemplates())
    }

    suspend fun createConversation(
        title: String,
        modelId: String,
        personaId: String,
        projectId: String? = null
    ): ConversationEntity {
        val now = System.currentTimeMillis()
        val conversation = ConversationEntity(
            id = UUID.randomUUID().toString(),
            title = title.take(64).ifBlank { "New Conversation" },
            modelId = modelId,
            personaId = personaId,
            isPinned = false,
            createdAt = now,
            updatedAt = now,
            projectId = projectId
        )
        dao.insertConversation(conversation)
        return conversation
    }

    suspend fun getConversation(id: String): ConversationEntity? = dao.getConversationById(id)

    suspend fun getMessagesOnce(conversationId: String): List<MessageEntity> =
        dao.getMessagesOnce(conversationId)

    suspend fun saveMessage(message: MessageEntity) {
        dao.upsertMessage(message)
        dao.touchConversation(message.conversationId, System.currentTimeMillis())
    }

    suspend fun deleteMessage(messageId: String) = dao.deleteMessage(messageId)

    suspend fun toggleBookmark(messageId: String, currentValue: Boolean) =
        dao.setBookmarked(messageId, !currentValue)

    suspend fun togglePinMessage(messageId: String, currentPinned: Boolean) =
        dao.setMessagePinned(messageId, !currentPinned)

    suspend fun deleteMessagesFromTimestamp(conversationId: String, fromTimestamp: Long) =
        dao.deleteMessagesFromTimestamp(conversationId, fromTimestamp)

    suspend fun selectAssistantVariant(message: MessageEntity, targetIndex: Int, lockAsFinal: Boolean = false) {
        val variants = com.example.data.local.parseMessageVariants(message.variantsJson)
        if (variants.isEmpty()) return
        val safeIdx = targetIndex.coerceIn(0, variants.lastIndex)
        val chosen = variants[safeIdx]
        val nextVariantsJson = if (lockAsFinal) null else message.variantsJson
        val nextIndex = if (lockAsFinal) 0 else safeIdx
        dao.updateMessageVariantSelection(
            messageId = message.id,
            content = chosen.content,
            thinkingContent = chosen.thinkingContent,
            thinkingDurationMs = chosen.thinkingDurationMs,
            sourcesJson = chosen.sourcesJson,
            variantsJson = nextVariantsJson,
            selectedVariantIndex = nextIndex
        )
    }

    suspend fun forkConversationFromMessage(
        sourceConversationId: String,
        upToMessageId: String
    ): ConversationEntity? {
        val sourceConvo = dao.getConversationById(sourceConversationId) ?: return null
        val allMessages = dao.getMessagesOnce(sourceConversationId)
        val cutoffIndex = allMessages.indexOfFirst { it.id == upToMessageId }
        val sliced = if (cutoffIndex >= 0) allMessages.take(cutoffIndex + 1) else allMessages
        val now = System.currentTimeMillis()
        val forkedId = UUID.randomUUID().toString()
        val cleanBaseTitle = sourceConvo.title.removeSuffix(" (Fork)").take(54)
        val forkedConvo = sourceConvo.copy(
            id = forkedId,
            title = "$cleanBaseTitle (Fork)",
            isPinned = false,
            createdAt = now,
            updatedAt = now
        )
        dao.insertConversation(forkedConvo)
        if (sliced.isNotEmpty()) {
            val clonedMessages = sliced.map { msg ->
                msg.copy(
                    id = UUID.randomUUID().toString(),
                    conversationId = forkedId
                )
            }
            dao.upsertMessages(clonedMessages)
        }
        return forkedConvo
    }

    suspend fun renameConversation(id: String, newTitle: String) {
        if (newTitle.isNotBlank()) {
            dao.renameConversation(id, newTitle.trim())
        }
    }

    suspend fun togglePinConversation(id: String, currentPinned: Boolean) =
        dao.setConversationPinned(id, !currentPinned)

    suspend fun updateConversationModel(id: String, modelId: String) =
        dao.updateConversationModel(id, modelId)

    suspend fun updateConversationPersona(id: String, personaId: String) =
        dao.updateConversationPersona(id, personaId)

    suspend fun updateConversationProject(id: String, projectId: String?) =
        dao.updateConversationProject(id, projectId)

    suspend fun deleteConversation(id: String) = dao.deleteConversation(id)

    suspend fun clearAllHistory() = dao.deleteAllConversations()

    suspend fun createCustomPersona(
        name: String,
        roleTag: String,
        description: String,
        systemPrompt: String,
        iconKey: String
    ): PersonaEntity {
        val persona = PersonaEntity(
            id = "custom_${UUID.randomUUID()}",
            name = name.trim(),
            roleTag = roleTag.trim().ifBlank { "Custom" },
            description = description.trim(),
            systemPrompt = systemPrompt.trim(),
            iconKey = iconKey,
            isCustom = true
        )
        dao.upsertPersona(persona)
        return persona
    }

    suspend fun deleteCustomPersona(id: String) = dao.deleteCustomPersona(id)

    // Personal Memory Vault ("Aura Remembers")
    suspend fun getActiveMemoriesOnce(): List<MemoryEntity> = dao.getActiveMemoriesOnce()

    suspend fun addMemory(content: String, category: String = "General"): MemoryEntity {
        val now = System.currentTimeMillis()
        val memory = MemoryEntity(
            id = UUID.randomUUID().toString(),
            content = content.trim(),
            category = category.trim().ifBlank { "General" },
            isEnabled = true,
            createdAt = now,
            updatedAt = now
        )
        dao.upsertMemory(memory)
        return memory
    }

    suspend fun toggleMemory(id: String, currentEnabled: Boolean) =
        dao.setMemoryEnabled(id, !currentEnabled)

    suspend fun deleteMemory(id: String) = dao.deleteMemory(id)

    // Private Document Knowledge Vault (On-Device RAG)
    suspend fun getAllKnowledgeChunksOnce(): List<KnowledgeChunkEntity> =
        dao.getAllKnowledgeChunksOnce()

    suspend fun saveKnowledgeDocument(
        doc: KnowledgeDocEntity,
        chunks: List<KnowledgeChunkEntity>
    ) {
        dao.upsertKnowledgeDoc(doc)
        if (chunks.isNotEmpty()) {
            dao.upsertKnowledgeChunks(chunks)
        }
    }

    suspend fun deleteKnowledgeDoc(docId: String) = dao.deleteKnowledgeDoc(docId)

    // Deep Global Chat & Code Search
    suspend fun searchAllMessages(query: String): List<MessageSearchHit> {
        val cleaned = query.trim()
        if (cleaned.length < 2) return emptyList()
        return dao.searchMessagesWithConversations(cleaned, limit = 25)
    }

    // Aura Autopilot Background Scheduled Agents
    fun observeAutopilotTasks(): Flow<List<AutopilotTaskEntity>> = dao.observeAutopilotTasks()

    suspend fun getAutopilotTaskById(id: String): AutopilotTaskEntity? = dao.getAutopilotTaskById(id)

    suspend fun createAutopilotTask(
        title: String,
        prompt: String,
        intervalHours: Int,
        useWebSearch: Boolean
    ): AutopilotTaskEntity {
        val task = AutopilotTaskEntity(
            id = UUID.randomUUID().toString(),
            title = title.trim().take(60),
            prompt = prompt.trim(),
            intervalHours = intervalHours.coerceIn(1, 168),
            useWebSearch = useWebSearch,
            isEnabled = true,
            createdAt = System.currentTimeMillis()
        )
        dao.upsertAutopilotTask(task)
        return task
    }

    suspend fun setAutopilotTaskEnabled(id: String, enabled: Boolean) =
        dao.setAutopilotTaskEnabled(id, enabled)

    suspend fun deleteAutopilotTask(id: String) = dao.deleteAutopilotTask(id)

    // Claude-Style Project Workspaces
    suspend fun getProjectById(id: String): ProjectEntity? = dao.getProjectById(id)

    suspend fun saveProject(
        id: String? = null,
        name: String,
        description: String,
        customInstructions: String,
        pinnedUrlsOrNotes: String,
        accentHex: String = "#7AA2FF"
    ): ProjectEntity {
        val now = System.currentTimeMillis()
        val existing = id?.let { dao.getProjectById(it) }
        val project = ProjectEntity(
            id = existing?.id ?: UUID.randomUUID().toString(),
            name = name.trim().take(48).ifBlank { "Workspace" },
            description = description.trim().take(160),
            customInstructions = customInstructions.trim(),
            pinnedUrlsOrNotes = pinnedUrlsOrNotes.trim(),
            accentHex = accentHex,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now
        )
        dao.upsertProject(project)
        return project
    }

    suspend fun deleteProject(projectId: String) {
        dao.clearProjectFromConversations(projectId)
        dao.deleteProject(projectId)
    }

    // Versioned Artifact Hub
    suspend fun extractAndSaveArtifactsFromMessage(
        conversationId: String,
        messageId: String,
        markdownContent: String
    ): List<ArtifactEntity> {
        val extracted = extractArtifactsFromMarkdown(markdownContent)
        if (extracted.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()
        val saved = mutableListOf<ArtifactEntity>()
        for ((idx, item) in extracted.withIndex()) {
            val (lang, title, code) = item
            val prevVersion = dao.getArtifactMaxVersionForTitle(title) ?: 0
            val entity = ArtifactEntity(
                id = UUID.randomUUID().toString(),
                conversationId = conversationId,
                messageId = messageId,
                title = title,
                language = lang,
                code = code,
                versionNumber = prevVersion + 1,
                createdAt = now + idx
            )
            dao.upsertArtifact(entity)
            saved += entity
        }
        return saved
    }

    suspend fun deleteArtifact(id: String) = dao.deleteArtifact(id)

    // Reusable AI Studio Prompt Library
    suspend fun createCustomPromptTemplate(
        title: String,
        category: String,
        systemPrompt: String,
        userTemplate: String
    ): PromptTemplateEntity {
        val template = PromptTemplateEntity(
            id = "tpl_${UUID.randomUUID()}",
            title = title.trim().take(60).ifBlank { "Custom Prompt" },
            category = category.trim().ifBlank { "Custom" },
            systemPrompt = systemPrompt.trim(),
            userTemplate = userTemplate.trim(),
            isBuiltIn = false,
            createdAt = System.currentTimeMillis()
        )
        dao.upsertPromptTemplate(template)
        return template
    }

    suspend fun deleteCustomPromptTemplate(id: String) = dao.deleteCustomPromptTemplate(id)

    // Custom Webhook / REST Tools (MCP-Lite)
    suspend fun getEnabledWebhooksOnce(): List<CustomWebhookEntity> = dao.getEnabledCustomWebhooksOnce()

    suspend fun createCustomWebhook(
        name: String,
        description: String,
        endpointUrl: String,
        httpMethod: String,
        headersJson: String = "",
        bodyTemplate: String = ""
    ): CustomWebhookEntity {
        val webhook = CustomWebhookEntity(
            id = UUID.randomUUID().toString(),
            name = name.trim().take(48).ifBlank { "Custom Tool" },
            description = description.trim().take(140),
            endpointUrl = endpointUrl.trim(),
            httpMethod = if (httpMethod.equals("POST", ignoreCase = true)) "POST" else "GET",
            headersJson = headersJson.trim(),
            bodyTemplate = bodyTemplate.trim(),
            isEnabled = true,
            createdAt = System.currentTimeMillis()
        )
        dao.upsertCustomWebhook(webhook)
        return webhook
    }

    suspend fun setCustomWebhookEnabled(id: String, enabled: Boolean) =
        dao.setCustomWebhookEnabled(id, enabled)

    suspend fun deleteCustomWebhook(id: String) = dao.deleteCustomWebhook(id)

    companion object {
        private val ARTIFACT_PREVIEW_LANGS = setOf(
            "html", "htm", "svg", "mermaid", "js", "javascript",
            "jsx", "react", "tailwind", "slides", "deck", "csv", "tsv"
        )

        fun extractArtifactsFromMarkdown(markdown: String): List<Triple<String, String, String>> {
            val regex = Regex("```([A-Za-z0-9_+./:-]*)\\n([\\s\\S]*?)```")
            val results = mutableListOf<Triple<String, String, String>>()
            var counter = 1
            for (match in regex.findAll(markdown)) {
                val rawHeader = match.groupValues[1].trim()
                val code = match.groupValues[2].trim()
                if (code.length < 24) continue
                val colonIdx = rawHeader.indexOf(':')
                val lang = (if (colonIdx > 0) rawHeader.substring(0, colonIdx) else rawHeader)
                    .trim()
                    .lowercase()
                    .ifBlank { "code" }
                val headerFile = if (colonIdx > 0) rawHeader.substring(colonIdx + 1).trim().takeIf { it.isNotBlank() } else null
                val firstLine = code.lineSequence().firstOrNull()?.trim().orEmpty()
                val commentFile = Regex("^(?://|#|--|/\\*)\\s*File:\\s*([^\\s*]+)", RegexOption.IGNORE_CASE)
                    .find(firstLine)?.groupValues?.getOrNull(1)?.trim()
                val explicitTitle = headerFile ?: commentFile

                val isIndexable = lang in ARTIFACT_PREVIEW_LANGS || (explicitTitle != null && code.length >= 60)
                if (!isIndexable) continue

                val resolvedTitle = explicitTitle ?: when (lang) {
                    "html", "htm" -> "Interactive Web Artifact #$counter"
                    "svg" -> "SVG Graphic #$counter"
                    "mermaid" -> "Architecture Diagram #$counter"
                    "slides", "deck" -> "Slide Deck #$counter"
                    "csv", "tsv" -> "Dataset Chart #$counter"
                    "js", "javascript", "jsx", "react" -> "JS Component #$counter"
                    else -> "${lang.uppercase()} Snippet #$counter"
                }
                counter++
                results += Triple(lang, resolvedTitle, code)
            }
            return results
        }

        fun extractTemplateVariables(template: String): List<String> {
            return Regex("\\{\\{\\s*([A-Za-z0-9_ .-]+?)\\s*\\}\\}")
                .findAll(template)
                .map { it.groupValues[1].trim() }
                .distinct()
                .toList()
        }

        fun substituteTemplateVariables(template: String, values: Map<String, String>): String {
            return Regex("\\{\\{\\s*([A-Za-z0-9_ .-]+?)\\s*\\}\\}").replace(template) { match ->
                val key = match.groupValues[1].trim()
                values[key]?.takeIf { it.isNotBlank() } ?: match.value
            }
        }
    }

    private fun defaultPromptTemplates(): List<PromptTemplateEntity> = listOf(
        PromptTemplateEntity(
            id = "tpl_builtin_arch",
            title = "System Architecture Spec",
            category = "Engineering",
            systemPrompt = "Think like a Staff Systems Architect. Emphasize scalability, failure domains, data schemas, and crisp trade-off tables.",
            userTemplate = "Design the complete system architecture for {{project}} supporting {{scale}} users. Focus on {{constraints}}.",
            isBuiltIn = true,
            createdAt = 1L
        ),
        PromptTemplateEntity(
            id = "tpl_builtin_audit",
            title = "Zero-Bug Code Review",
            category = "Engineering",
            systemPrompt = "Strictly audit for security, concurrency race conditions, memory leaks, null crashes, and missing edge cases. Output a diff fix.",
            userTemplate = "Audit this {{language}} implementation for {{focus_area}} bugs and return a hardened diff:\n\n{{code_or_issue}}",
            isBuiltIn = true,
            createdAt = 2L
        ),
        PromptTemplateEntity(
            id = "tpl_builtin_deck",
            title = "Executive Slide Deck",
            category = "Presentations",
            systemPrompt = "Output a crisp 6-slide presentation inside a ```slides block using --- separators.",
            userTemplate = "Build a 6-slide executive deck on {{topic}} for {{audience}} covering strategy, metrics, risks, and 90-day roadmap.",
            isBuiltIn = true,
            createdAt = 3L
        ),
        PromptTemplateEntity(
            id = "tpl_builtin_dataset",
            title = "Dataset & Chart Generator",
            category = "Analytics",
            systemPrompt = "Produce clean, realistic CSV data in a ```csv block with numeric columns ready for chart plotting.",
            userTemplate = "Create a realistic ```csv comparison dataset for {{subject}} across {{metric}} and provide 3 executive takeaways.",
            isBuiltIn = true,
            createdAt = 4L
        )
    )

    private fun defaultPersonas(): List<PersonaEntity> = listOf(
        PersonaEntity(
            id = "persona_general",
            name = "Aura Core",
            roleTag = "All-Purpose",
            description = "Executive clarity for coding, KaTeX math, reasoning & everyday mastery.",
            systemPrompt = "You are Aura AI, created by Abhi at Abhynex. Deliver publication-grade structured clarity, action-first answers, Markdown trade-off tables, KaTeX equations, and Principal-Engineer zero-error code.",
            iconKey = "sparkle",
            createdAt = 1L
        ),
        PersonaEntity(
            id = "persona_engineer",
            name = "Code Architect",
            roleTag = "Staff Engineer",
            description = "Principal-tier zero-error coding: architecture, full imports, concurrency safety & tests.",
            systemPrompt = "Act as a Principal Software Engineer & Systems Architect. Start with a crisp architectural invariant plan, deliver 100% complete compile-ready code organized by file (`// File: ...`) with full imports, strict typing, null/coroutine/concurrency safety, zero placeholders or ellipsis, unified ```diff patches for fixes, and deterministic edge-case tests.",
            iconKey = "code",
            createdAt = 2L
        ),
        PersonaEntity(
            id = "persona_reviewer",
            name = "Bug Hunter",
            roleTag = "Zero-Error Audit",
            description = "Principal code reviewer hunting race conditions, crashes, leaks & security flaws.",
            systemPrompt = "Act as a Principal Security & Reliability Reviewer. Critically audit code for null-pointer crashes, race conditions, memory/coroutine leaks, SQL/auth vulnerabilities, off-by-one errors, and silent failures. List findings by severity (Critical/High/Medium), explain root causes, and provide the complete drop-in fixed code.",
            iconKey = "bug",
            createdAt = 3L
        ),
        PersonaEntity(
            id = "persona_math",
            name = "Math & STEM Pro",
            roleTag = "KaTeX Olympiad",
            description = "Step-by-step JEE, Olympiad, Calculus & Physics derivations with boxed verification.",
            systemPrompt = "Act as an Olympiad & JEE Advanced Mathematics/Physics Professor. Never wait for sample problems when asked for hard questions — select a genuinely challenging problem and solve every step clearly with KaTeX display blocks ($$...$$), sanity-check the result, and place the final answer in $$\\boxed{...}$$.",
            iconKey = "math",
            createdAt = 4L
        ),
        PersonaEntity(
            id = "persona_analyst",
            name = "Deep Researcher",
            roleTag = "Strategy & Web",
            description = "First-principles research, Markdown trade-off matrices & verified executive briefs.",
            systemPrompt = "Act as a Principal Strategy & Technical Researcher. Synthesize complex topics from first principles using structured Markdown comparison tables, quantifiable trade-offs, risk matrices, and concise actionable recommendations.",
            iconKey = "search",
            createdAt = 5L
        ),
        PersonaEntity(
            id = "persona_writer",
            name = "Marathi & Copy Pro",
            roleTag = "Voice & Craft",
            description = "Sharp, natural Marathi, Romanized Marathi, Hindi & English communication.",
            systemPrompt = "Act as a master multilingual editor and communicator fluent in Marathi, Romanized Marathi, Hindi, and English. Match the user's exact language and register effortlessly with crisp, vivid, zero-fluff prose.",
            iconKey = "edit",
            createdAt = 6L
        )
    )
}

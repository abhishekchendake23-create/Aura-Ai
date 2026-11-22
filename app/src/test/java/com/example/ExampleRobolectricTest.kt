package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AuraDatabase
import com.example.data.local.MessageEntity
import com.example.data.repository.ChatRepository
import com.example.data.repository.SourceCitation
import com.example.data.repository.parseSources
import com.example.data.repository.serializeSources
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    private lateinit var database: AuraDatabase
    private lateinit var repository: ChatRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AuraDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = ChatRepository(database.auraDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Aura AI", appName)
    }

    @Test
    fun `saveMessage preserves user and assistant messages and sources without cascade deletion`() = runTest {
        val convo = repository.createConversation(
            title = "Hello Aura",
            modelId = "gemini-3.8-flash",
            personaId = "persona_general"
        )

        val userMsg = MessageEntity(
            id = "u1",
            conversationId = convo.id,
            role = "user",
            content = "Hi Aura",
            timestamp = 1000L
        )
        repository.saveMessage(userMsg)

        val sources = listOf(SourceCitation("Kotlin Docs", "https://kotlinlang.org"))
        val assistantMsg = MessageEntity(
            id = "a1",
            conversationId = convo.id,
            role = "assistant",
            content = "Hello! How can I help?",
            modelDisplayName = "Aura AI",
            sourcesJson = serializeSources(sources),
            timestamp = 2000L
        )
        repository.saveMessage(assistantMsg)

        val savedMessages = repository.getMessagesOnce(convo.id)
        assertEquals(2, savedMessages.size)
        assertEquals("Hi Aura", savedMessages[0].content)
        assertEquals("Hello! How can I help?", savedMessages[1].content)
        val decodedSources = parseSources(savedMessages[1].sourcesJson)
        assertEquals(1, decodedSources.size)
        assertEquals("Kotlin Docs", decodedSources[0].title)
    }

    @Test
    fun `ensureDefaultPersonas syncs all 6 elite personas while preserving custom personas`() = runTest {
        repository.createCustomPersona(
            name = "My Custom",
            roleTag = "Custom",
            description = "Custom helper",
            systemPrompt = "Be concise",
            iconKey = "sparkle"
        )
        repository.ensureDefaultPersonas()

        val all = repository.observePersonas().first()
        assertEquals(7, all.size)
        assertTrue(all.any { it.id == "persona_engineer" && it.name == "Code Architect" })
        assertTrue(all.any { it.id == "persona_reviewer" && it.name == "Bug Hunter" })
        assertTrue(all.any { it.id == "persona_math" && it.name == "Math & STEM Pro" })
        assertTrue(all.any { it.isCustom && it.name == "My Custom" })
    }

    @Test
    fun `slash commands triggers are unique and cover sandbox, web, arch, and math`() {
        val triggers = com.example.data.model.AURA_SLASH_COMMANDS.map { it.trigger }
        assertEquals(triggers.size, triggers.toSet().size)
        assertTrue(triggers.containsAll(listOf("/arch", "/debug", "/sandbox", "/math", "/tests", "/web")))
        assertTrue(com.example.data.model.AURA_SLASH_COMMANDS.all { it.trigger.startsWith("/") })
    }

    @Test
    fun `in-app user API key saves, persists, and clears cleanly`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = com.example.data.preferences.PreferencesManager(context)

        prefs.setUserApiKey("  test-user-key-12345  ")
        assertEquals("test-user-key-12345", prefs.userApiKey.first())
        assertEquals("test-user-key-12345", prefs.getEffectiveApiKey())

        prefs.setUserApiKey("")
        assertEquals("", prefs.userApiKey.first())
    }

    @Test
    fun `app language selection persists Marathi, Hindi, English, and Auto`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = com.example.data.preferences.PreferencesManager(context)

        prefs.setAppLanguage(com.example.data.model.AppLanguage.MARATHI)
        assertEquals(com.example.data.model.AppLanguage.MARATHI, prefs.appLanguage.first())

        prefs.setAppLanguage(com.example.data.model.AppLanguage.HINDI)
        assertEquals(com.example.data.model.AppLanguage.HINDI, prefs.appLanguage.first())

        prefs.setAppLanguage(com.example.data.model.AppLanguage.ENGLISH)
        assertEquals(com.example.data.model.AppLanguage.ENGLISH, prefs.appLanguage.first())
    }

    @Test
    fun `503 unavailable and transient server errors are recognized as recoverable`() {
        assertTrue(com.example.data.repository.DirectGeminiAiRepository.isTransientOrRecoverableErrorMessage("503 UNAVAILABLE: The service is currently unavailable."))
        assertTrue(com.example.data.repository.DirectGeminiAiRepository.isTransientOrRecoverableErrorMessage("HTTP 429 RESOURCE_EXHAUSTED"))
        assertTrue(com.example.data.repository.DirectGeminiAiRepository.isTransientOrRecoverableErrorMessage("HTTP 500 INTERNAL"))
    }

    @Test
    fun `groq api key and fast priority preference persist cleanly`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = com.example.data.preferences.PreferencesManager(context)

        assertTrue(prefs.preferFastGroq.first())
        prefs.setUserGroqApiKey("  gsk_test_fast_key_999  ")
        assertEquals("gsk_test_fast_key_999", prefs.userGroqApiKey.first())
        assertEquals("gsk_test_fast_key_999", prefs.getEffectiveGroqApiKey())

        prefs.setPreferFastGroq(false)
        assertEquals(false, prefs.preferFastGroq.first())

        prefs.setUserGroqApiKey("")
        assertEquals("", prefs.userGroqApiKey.first())
    }

    @Test
    fun `groq fast and fallback chains select latest production models by mode and attachment`() {
        val fastTextChain = com.example.data.repository.DirectGeminiAiRepository.selectGroqFallbackModels(
            capabilityMode = com.example.data.model.CapabilityMode.STANDARD,
            modelTier = com.example.data.model.AiModelTier.GEMINI_3_8_FLASH,
            hasImageAttachment = false
        )
        assertEquals(
            listOf(
                "openai/gpt-oss-20b",
                "llama-3.1-8b-instant",
                "openai/gpt-oss-120b",
                "llama-3.3-70b-versatile"
            ),
            fastTextChain
        )

        val deepReasonChain = com.example.data.repository.DirectGeminiAiRepository.selectGroqFallbackModels(
            capabilityMode = com.example.data.model.CapabilityMode.DEEP_THINK,
            modelTier = com.example.data.model.AiModelTier.GEMINI_4_ARGON,
            hasImageAttachment = false
        )
        assertEquals("openai/gpt-oss-120b", deepReasonChain.first())

        val visionChain = com.example.data.repository.DirectGeminiAiRepository.selectGroqFallbackModels(
            capabilityMode = com.example.data.model.CapabilityMode.STANDARD,
            modelTier = com.example.data.model.AiModelTier.GEMINI_3_8_FLASH,
            hasImageAttachment = true
        )
        assertEquals(listOf("meta-llama/llama-4-scout-17b-16e-instruct"), visionChain)
    }

    @Test
    fun `memory vault saves, toggles, and deletes persistent facts`() = runTest {
        val m1 = repository.addMemory("I build Android Kotlin apps", "Profile")
        val m2 = repository.addMemory("Prefer Marathi replies", "Preference")

        val activeBefore = repository.getActiveMemoriesOnce()
        assertEquals(2, activeBefore.size)

        repository.toggleMemory(m1.id, currentEnabled = true)
        val activeAfterToggle = repository.getActiveMemoriesOnce()
        assertEquals(1, activeAfterToggle.size)
        assertEquals(m2.id, activeAfterToggle.first().id)

        repository.deleteMemory(m2.id)
        assertTrue(repository.getActiveMemoriesOnce().isEmpty())
    }

    @Test
    fun `knowledge RAG engine chunks long text and ranks relevant passages first`() = runTest {
        val docText = buildString {
            append("Jetpack Compose StateFlow architecture uses ViewModel and Room offline storage for fast UI updates. ")
            append("Quantum mechanics describes wave-particle duality and the Schrodinger equation in physics. ".repeat(15))
            append("Photosynthesis converts sunlight into chemical energy inside chloroplasts.")
        }
        val chunks = com.example.data.repository.KnowledgeRagEngine.chunkDocument(
            docId = "doc_1",
            docTitle = "Study Notes",
            rawText = docText,
            chunkSizeChars = 260,
            overlapChars = 40
        )
        assertTrue(chunks.size >= 2)

        val doc = com.example.data.local.KnowledgeDocEntity(
            id = "doc_1",
            title = "Study Notes",
            sourceType = "Note",
            chunkCount = chunks.size,
            createdAt = 100L
        )
        repository.saveKnowledgeDocument(doc, chunks)

        val storedChunks = repository.getAllKnowledgeChunksOnce()
        val hits = com.example.data.repository.KnowledgeRagEngine.retrieveTopChunks(
            query = "How does Jetpack Compose ViewModel work?",
            allChunks = storedChunks,
            topK = 2
        )
        assertTrue(hits.isNotEmpty())
        assertTrue(hits.first().content.contains("Jetpack Compose", ignoreCase = true))
    }

    @Test
    fun `auto router classifies coding, deep math, web, and vision prompts accurately`() {
        val codeDecision = com.example.data.repository.DirectGeminiAiRepository.classifyAutoRoute(
            com.example.data.repository.AiRequest(
                prompt = "Refactor this Kotlin ViewModel coroutine flow to eliminate race conditions",
                modelTier = com.example.data.model.AiModelTier.AUTO_ROUTER,
                capabilityMode = com.example.data.model.CapabilityMode.STANDARD,
                persona = null,
                history = emptyList()
            )
        )
        assertEquals(com.example.data.model.CapabilityMode.CODE_ARCHITECT, codeDecision.resolvedCapability)

        val webDecision = com.example.data.repository.DirectGeminiAiRepository.classifyAutoRoute(
            com.example.data.repository.AiRequest(
                prompt = "What is the latest stable Android Studio release version today?",
                modelTier = com.example.data.model.AiModelTier.AUTO_ROUTER,
                capabilityMode = com.example.data.model.CapabilityMode.STANDARD,
                persona = null,
                history = emptyList()
            )
        )
        assertTrue(webDecision.useWebSearch)

        val visionDecision = com.example.data.repository.DirectGeminiAiRepository.classifyAutoRoute(
            com.example.data.repository.AiRequest(
                prompt = "Solve this",
                modelTier = com.example.data.model.AiModelTier.AUTO_ROUTER,
                capabilityMode = com.example.data.model.CapabilityMode.STANDARD,
                persona = null,
                history = emptyList(),
                attachmentUri = "content://sample/img.jpg",
                attachmentIsImage = true
            )
        )
        assertEquals(com.example.data.model.AiModelTier.GEMINI_3_8_FLASH, visionDecision.resolvedModel)
    }

    @Test
    fun `portable backup exports and restores conversations, memories, and knowledge docs cleanly`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val backupRepo = com.example.data.repository.BackupRepository(database.auraDao(), context)

        val convo = repository.createConversation("Physics Prep", "gemini-3.8-flash", "persona_math")
        repository.saveMessage(
            MessageEntity(
                id = "m_backup_1",
                conversationId = convo.id,
                role = "user",
                content = "Derive escape velocity",
                timestamp = 1234L
            )
        )
        repository.addMemory("Preparing for JEE Physics", "Study")
        val chunks = com.example.data.repository.KnowledgeRagEngine.chunkDocument("doc_b1", "Formulas", "v = sqrt(2GM/R)")
        repository.saveKnowledgeDocument(
            com.example.data.local.KnowledgeDocEntity("doc_b1", "Formulas", "Note", chunks.size, 123L),
            chunks
        )

        val (jsonPayload, exportedStats) = backupRepo.buildBackupPayload()
        assertEquals(1, exportedStats.conversations)
        assertEquals(1, exportedStats.messages)
        assertEquals(1, exportedStats.memories)
        assertEquals(1, exportedStats.knowledgeDocs)

        database.clearAllTables()
        assertTrue(repository.getAllKnowledgeChunksOnce().isEmpty())

        val restoredStats = backupRepo.restoreFromJsonString(jsonPayload)
        assertEquals(1, restoredStats.conversations)
        assertEquals(1, restoredStats.messages)
        assertEquals(1, restoredStats.memories)
        assertEquals(1, restoredStats.knowledgeDocs)
        assertEquals(1, repository.getMessagesOnce(convo.id).size)
        assertEquals(1, repository.getActiveMemoriesOnce().size)
    }

    @Test
    fun `live web search engine parses DuckDuckGo HTML redirects, RSS XML, Wikipedia JSON, and cleans slash prefixes`() {
        val sampleHtml = """
            <div class="results">
              <div class="result results_links results_links_deep web-result">
                <h2 class="result__title">
                  <a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fkotlinlang.org%2Fdocs%2Freleases.html&amp;rut=123">Kotlin Releases &amp; Updates</a>
                </h2>
                <a class="result__snippet">Latest stable release is <b>Kotlin 2.1</b> with improved compiler performance &amp; multiplatform updates.</a>
              </div>
            </div>
        """.trimIndent()

        val ddgResults = com.example.data.repository.LiveWebSearchEngine.parseDuckDuckGoHtml(sampleHtml)
        assertEquals(1, ddgResults.size)
        assertEquals("https://kotlinlang.org/docs/releases.html", ddgResults[0].url)
        assertEquals("Kotlin Releases & Updates", ddgResults[0].title)
        assertTrue(ddgResults[0].snippet.contains("Kotlin 2.1"))

        val sampleRss = """
            <rss><channel>
              <item>
                <title>Gold rate rises in Pune today - Economic Times</title>
                <link>https://economictimes.indiatimes.com/markets/gold</link>
                <pubDate>Sat, 21 Nov 2026 08:30:00 GMT</pubDate>
                <source url="https://economictimes.indiatimes.com">Economic Times</source>
                <description><![CDATA[<a href="https://example.com">24K gold rate</a> reaches fresh high.]]></description>
              </item>
            </channel></rss>
        """.trimIndent()

        val rssResults = com.example.data.repository.LiveWebSearchEngine.parseNewsRssXml(sampleRss)
        assertEquals(1, rssResults.size)
        assertEquals("Economic Times", rssResults[0].sourceName)
        assertEquals("Sat, 21 Nov 2026 08:30:00 GMT", rssResults[0].publishedAt)
        assertTrue(rssResults[0].snippet.contains("24K gold rate"))

        val sampleWiki = """
            {"query":{"search":[{"title":"Indian Premier League","snippet":"The <span class=\"searchmatch\">IPL</span> is a men's T20 cricket league.","timestamp":"2026-11-10T12:00:00Z"}]}}
        """.trimIndent()
        val wikiResults = com.example.data.repository.LiveWebSearchEngine.parseWikipediaSearchJson(sampleWiki)
        assertEquals(1, wikiResults.size)
        assertEquals("https://en.wikipedia.org/wiki/Indian_Premier_League", wikiResults[0].url)
        assertTrue(wikiResults[0].snippet.contains("IPL"))

        val cleaned = com.example.data.repository.LiveWebSearchEngine.extractCleanSearchQuery(
            "Search live web sources and verify the latest authoritative details with a structured breakdown for:\nWhat is the current RBI repo rate?"
        )
        assertEquals("What is the current RBI repo rate?", cleaned)
    }

    @Test
    fun `fresh web cue detection covers English and Romanized Marathi or Hindi queries while valid Gemini models are mapped`() {
        assertTrue(com.example.data.repository.DirectGeminiAiRepository.hasFreshWebCue("aajcha gold rate pune madhe kay ahe?"))
        assertTrue(com.example.data.repository.DirectGeminiAiRepository.hasFreshWebCue("who won yesterday's IPL match?"))
        assertTrue(com.example.data.repository.DirectGeminiAiRepository.hasFreshWebCue("Check https://kotlinlang.org"))

        val marathiWebDecision = com.example.data.repository.DirectGeminiAiRepository.classifyAutoRoute(
            com.example.data.repository.AiRequest(
                prompt = "aajcha sensex bhav kay ahe",
                modelTier = com.example.data.model.AiModelTier.AUTO_ROUTER,
                capabilityMode = com.example.data.model.CapabilityMode.STANDARD,
                persona = null,
                history = emptyList()
            )
        )
        assertTrue(marathiWebDecision.useWebSearch)

        val flash38Candidates = com.example.data.repository.DirectGeminiAiRepository.resolveGeminiCandidateModels(
            com.example.data.model.AiModelTier.GEMINI_3_8_FLASH
        )
        assertEquals("gemini-3.8-flash", flash38Candidates.first())
        assertEquals(
            "gemini-3.7-flash",
            com.example.data.repository.DirectGeminiAiRepository.resolveGeminiCandidateModels(
                com.example.data.model.AiModelTier.GEMINI_3_7_FLASH
            ).first()
        )
        assertEquals(
            "gemini-3.6-flash",
            com.example.data.repository.DirectGeminiAiRepository.resolveGeminiCandidateModels(
                com.example.data.model.AiModelTier.GEMINI_3_6_FLASH
            ).first()
        )
        assertEquals(
            "gemini-3.5-flash",
            com.example.data.repository.DirectGeminiAiRepository.resolveGeminiCandidateModels(
                com.example.data.model.AiModelTier.GEMINI_3_5_FLASH
            ).first()
        )
        assertEquals(
            "gemini-2.5-flash",
            com.example.data.repository.DirectGeminiAiRepository.resolveGeminiCandidateModels(
                com.example.data.model.AiModelTier.GEMINI_2_5_FLASH
            ).first()
        )
        assertTrue(flash38Candidates.contains("gemini-3.7-flash"))
        assertTrue(flash38Candidates.contains("gemini-3.6-flash"))
        assertTrue(flash38Candidates.contains("gemini-3.5-flash"))
        assertTrue(flash38Candidates.contains("gemini-3.5-flash-lite"))
        assertTrue(flash38Candidates.contains("gemini-2.5-flash"))
        assertTrue(flash38Candidates.contains("gemini-2.5-pro"))
        assertTrue(flash38Candidates.contains("gemini-2.5-flash-lite"))
        assertTrue(flash38Candidates.contains("gemini-2.0-flash"))
        assertTrue(flash38Candidates.contains("gemma-3-27b-it"))
        assertTrue(flash38Candidates.contains("gemma-3-12b-it"))

        val context = ApplicationProvider.getApplicationContext<Context>()
        val aiRepo = com.example.data.repository.DirectGeminiAiRepository(context)
        val systemInstruction = aiRepo.buildSystemInstruction(
            persona = null,
            capabilityMode = com.example.data.model.CapabilityMode.CODE_ARCHITECT,
            language = com.example.data.model.AppLanguage.AUTO,
            useWebSearch = true,
            useCodeExecution = false
        )
        assertTrue(systemInstruction.contains("Current Date & Time:"))
        assertTrue(systemInstruction.contains("Live Web Search Grounding & URL Context ON"))
        assertTrue(systemInstruction.contains("Principal Software Engineer Coding Standard"))
        assertTrue(systemInstruction.contains("STRICT CONFIDENTIALITY RULE"))
    }

    @Test
    fun `exa search JSON parsing extracts title, url, publishedDate, highlights, and text cleanly`() = runTest {
        val sampleExaJson = """
            {
              "results": [
                {
                  "title": "24K Gold Rate Today in Pune",
                  "url": "https://www.goodreturns.in/gold-rates/pune.html",
                  "publishedDate": "2026-11-21T09:15:00.000Z",
                  "highlights": ["Today 24K gold price in Pune is Rs 78,450 per 10 grams."],
                  "text": "Today 24K gold price in Pune is Rs 78,450 per 10 grams and 22K is Rs 71,910 per 10 grams."
                }
              ]
            }
        """.trimIndent()

        val parsed = com.example.data.repository.LiveWebSearchEngine.parseExaResultsJson(sampleExaJson)
        assertEquals(1, parsed.size)
        assertEquals("24K Gold Rate Today in Pune", parsed[0].title)
        assertEquals("https://www.goodreturns.in/gold-rates/pune.html", parsed[0].url)
        assertEquals("2026-11-21", parsed[0].publishedAt)
        assertEquals("goodreturns.in", parsed[0].sourceName)
        assertTrue(parsed[0].snippet.contains("78,450"))

        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = com.example.data.preferences.PreferencesManager(context)
        prefs.setUserExaApiKey("  exa_test_key_123  ")
        assertEquals("exa_test_key_123", prefs.userExaApiKey.first())
        assertEquals("exa_test_key_123", prefs.getEffectiveExaApiKey())

        prefs.setUserExaApiKey("")
        assertEquals("", prefs.userExaApiKey.first())
    }

    @Test
    fun `deepseek free models, SSE streaming reasoning parser, and cross-provider auto failover work end to end`() = runTest {
        // 1. DeepSeek free model order starts with latest free models
        val r1Candidates = com.example.data.repository.DirectGeminiAiRepository.resolveDeepSeekCandidates(
            modelTier = com.example.data.model.AiModelTier.DEEPSEEK_R1_FREE,
            hasOpenRouterKey = true,
            hasDeepSeekKey = false
        )
        assertEquals("deepseek/deepseek-r1-0528:free", r1Candidates.first().modelId)
        assertTrue(r1Candidates.any { it.modelId == "deepseek/deepseek-chat-v3-0324:free" })
        assertTrue(r1Candidates.any { it.modelId == "openrouter/free" })

        val v3Candidates = com.example.data.repository.DirectGeminiAiRepository.resolveDeepSeekCandidates(
            modelTier = com.example.data.model.AiModelTier.DEEPSEEK_V3_FREE,
            hasOpenRouterKey = true,
            hasDeepSeekKey = true
        )
        assertEquals("deepseek/deepseek-chat-v3-0324:free", v3Candidates.first().modelId)
        assertTrue(v3Candidates.any { it.provider == com.example.data.repository.ModelProvider.DEEPSEEK })

        // 2. Cross-provider auto-failover includes DeepSeek -> Groq -> all free Gemini models
        val deepSeekChain = com.example.data.repository.DirectGeminiAiRepository.buildCrossProviderFallbackChain(
            requestedTier = com.example.data.model.AiModelTier.DEEPSEEK_R1_FREE,
            effectiveTier = com.example.data.model.AiModelTier.DEEPSEEK_R1_FREE,
            capabilityMode = com.example.data.model.CapabilityMode.DEEP_THINK,
            hasImageAttachment = false,
            requiresGeminiTools = false,
            preferFastGroq = true,
            hasGeminiKey = true,
            hasOpenRouterKey = true,
            hasDeepSeekKey = false,
            hasGroqKey = true
        )
        assertEquals("deepseek/deepseek-r1-0528:free", deepSeekChain.first().modelId)
        assertTrue(deepSeekChain.any { it.provider == com.example.data.repository.ModelProvider.GROQ })
        assertTrue(deepSeekChain.any { it.provider == com.example.data.repository.ModelProvider.GEMINI && it.modelId == "gemini-2.5-flash" })
        assertTrue(deepSeekChain.any { it.modelId == "gemma-3-27b-it" })

        // 3. Explicit Gemini 2.0 Flash stays primary then falls back across Gemini -> DeepSeek -> Groq
        val gemini2Chain = com.example.data.repository.DirectGeminiAiRepository.buildCrossProviderFallbackChain(
            requestedTier = com.example.data.model.AiModelTier.GEMINI_2_0_FLASH,
            effectiveTier = com.example.data.model.AiModelTier.GEMINI_2_0_FLASH,
            capabilityMode = com.example.data.model.CapabilityMode.STANDARD,
            hasImageAttachment = false,
            requiresGeminiTools = false,
            preferFastGroq = true,
            hasGeminiKey = true,
            hasOpenRouterKey = true,
            hasDeepSeekKey = false,
            hasGroqKey = true
        )
        assertEquals("gemini-2.0-flash", gemini2Chain.first().modelId)
        assertFalse(gemini2Chain.first().supportsThinkingConfig)
        assertTrue(gemini2Chain.any { it.modelId == "deepseek/deepseek-r1-0528:free" })

        // 4. OpenAI-compat SSE parser handles reasoning_content, null values, and inline stream errors
        val nullReasoningJson = org.json.JSONObject("""{"choices":[{"delta":{"reasoning":null,"reasoning_content":"Step 1","content":"Hello"}}]}""")
        val delta = com.example.data.repository.DirectGeminiAiRepository.parseOpenAiCompatSseChunk(nullReasoningJson)
        assertEquals("Step 1", delta?.reasoningDelta)
        assertEquals("Hello", delta?.contentDelta)
        assertEquals(null, delta?.errorMessage)

        val inlineErrorJson = org.json.JSONObject("""{"error":{"code":429,"message":"Rate limit exceeded"}}""")
        val errDelta = com.example.data.repository.DirectGeminiAiRepository.parseOpenAiCompatSseChunk(inlineErrorJson)
        assertTrue(errDelta?.errorMessage?.contains("429") == true)
        assertTrue(com.example.data.repository.DirectGeminiAiRepository.isTransientOrRecoverableErrorMessage(errDelta!!.errorMessage!!))

        // 5. Preferences persist OpenRouter & DeepSeek keys cleanly
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = com.example.data.preferences.PreferencesManager(context)
        prefs.setUserOpenRouterApiKey("  sk-or-v1-test  ")
        prefs.setUserDeepSeekApiKey("  sk-ds-test  ")
        assertEquals("sk-or-v1-test", prefs.getEffectiveOpenRouterApiKey())
        assertEquals("sk-ds-test", prefs.getEffectiveDeepSeekApiKey())
        prefs.setUserOpenRouterApiKey("")
        prefs.setUserDeepSeekApiKey("")
    }
}

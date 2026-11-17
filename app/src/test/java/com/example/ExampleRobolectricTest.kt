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
}

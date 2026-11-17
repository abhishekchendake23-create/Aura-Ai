package com.example.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.BuildConfig
import com.example.data.model.ALL_30_VOICE_CHARACTERS
import com.example.data.model.AiModelTier
import com.example.data.model.AuraVoiceCharacter
import com.example.data.model.CapabilityMode
import com.example.data.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.auraDataStore: DataStore<Preferences> by preferencesDataStore(name = "aura_prefs")

class PreferencesManager(private val context: Context) {
    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DEFAULT_MODEL = stringPreferencesKey("default_model")
        val DEFAULT_CAPABILITY = stringPreferencesKey("default_capability")
        val ACTIVE_PERSONA = stringPreferencesKey("active_persona")
        val VOICE_CHARACTER = stringPreferencesKey("voice_character")
        val WEB_SEARCH_ENABLED = booleanPreferencesKey("web_search_enabled")
        val CODE_EXECUTION_ENABLED = booleanPreferencesKey("code_execution_enabled")
        val USER_API_KEY = stringPreferencesKey("user_api_key")
    }

    val themeMode: Flow<ThemeMode> = context.auraDataStore.data.map { prefs ->
        when (prefs[Keys.THEME_MODE]) {
            ThemeMode.LIGHT.name -> ThemeMode.LIGHT
            ThemeMode.SYSTEM.name -> ThemeMode.SYSTEM
            else -> ThemeMode.DARK
        }
    }

    val defaultModel: Flow<AiModelTier> = context.auraDataStore.data.map { prefs ->
        AiModelTier.fromId(prefs[Keys.DEFAULT_MODEL] ?: AiModelTier.GEMINI_3_8_FLASH.id)
    }

    val defaultCapability: Flow<CapabilityMode> = context.auraDataStore.data.map { prefs ->
        CapabilityMode.fromId(prefs[Keys.DEFAULT_CAPABILITY] ?: CapabilityMode.STANDARD.id)
    }

    val activePersonaId: Flow<String> = context.auraDataStore.data.map { prefs ->
        prefs[Keys.ACTIVE_PERSONA] ?: "persona_general"
    }

    val voiceCharacter: Flow<AuraVoiceCharacter> = context.auraDataStore.data.map { prefs ->
        val name = prefs[Keys.VOICE_CHARACTER] ?: "Zephyr"
        ALL_30_VOICE_CHARACTERS.find { it.apiName == name } ?: ALL_30_VOICE_CHARACTERS.first()
    }

    val webSearchEnabled: Flow<Boolean> = context.auraDataStore.data.map { prefs ->
        prefs[Keys.WEB_SEARCH_ENABLED] ?: false
    }

    val codeExecutionEnabled: Flow<Boolean> = context.auraDataStore.data.map { prefs ->
        prefs[Keys.CODE_EXECUTION_ENABLED] ?: false
    }

    val userApiKey: Flow<String> = context.auraDataStore.data.map { prefs ->
        prefs[Keys.USER_API_KEY]?.trim().orEmpty()
    }

    suspend fun getEffectiveApiKey(): String {
        val saved = userApiKey.first()
        if (saved.isNotBlank()) return saved
        return resolveBuildConfigApiKey()
    }

    suspend fun setUserApiKey(apiKey: String) {
        val cleaned = apiKey.trim()
        context.auraDataStore.edit { prefs ->
            if (cleaned.isEmpty()) {
                prefs.remove(Keys.USER_API_KEY)
            } else {
                prefs[Keys.USER_API_KEY] = cleaned
            }
        }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.auraDataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    suspend fun setDefaultModel(model: AiModelTier) {
        context.auraDataStore.edit { it[Keys.DEFAULT_MODEL] = model.id }
    }

    suspend fun setDefaultCapability(mode: CapabilityMode) {
        context.auraDataStore.edit { it[Keys.DEFAULT_CAPABILITY] = mode.id }
    }

    suspend fun setActivePersonaId(personaId: String) {
        context.auraDataStore.edit { it[Keys.ACTIVE_PERSONA] = personaId }
    }

    suspend fun setVoiceCharacter(apiName: String) {
        context.auraDataStore.edit { it[Keys.VOICE_CHARACTER] = apiName }
    }

    suspend fun setWebSearchEnabled(enabled: Boolean) {
        context.auraDataStore.edit { it[Keys.WEB_SEARCH_ENABLED] = enabled }
    }

    suspend fun setCodeExecutionEnabled(enabled: Boolean) {
        context.auraDataStore.edit { it[Keys.CODE_EXECUTION_ENABLED] = enabled }
    }

    companion object {
        fun resolveBuildConfigApiKey(): String {
            val builtIn = BuildConfig.GEMINI_API_KEY.trim()
            return if (builtIn.isBlank() ||
                builtIn == "YOUR_GEMINI_API_KEY" ||
                builtIn == "MY_NEW_API_KEY_DEFAULT_VALUE"
            ) "" else builtIn
        }
    }
}

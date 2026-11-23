package com.example.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.BuildConfig
import com.example.data.model.ALL_30_VOICE_CHARACTERS
import com.example.data.model.AiModelTier
import com.example.data.model.AppLanguage
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
        val APP_LANGUAGE = stringPreferencesKey("app_language")
        val WEB_SEARCH_ENABLED = booleanPreferencesKey("web_search_enabled")
        val CODE_EXECUTION_ENABLED = booleanPreferencesKey("code_execution_enabled")
        val USER_API_KEY = stringPreferencesKey("user_api_key")
        val USER_GROQ_API_KEY = stringPreferencesKey("user_groq_api_key")
        val USER_EXA_API_KEY = stringPreferencesKey("user_exa_api_key")
        val USER_OPENROUTER_API_KEY = stringPreferencesKey("user_openrouter_api_key")
        val USER_DEEPSEEK_API_KEY = stringPreferencesKey("user_deepseek_api_key")
        val PREFER_FAST_GROQ = booleanPreferencesKey("prefer_fast_groq")
        val VAULT_RAG_ENABLED = booleanPreferencesKey("vault_rag_enabled")
        val SELF_VERIFY_ENABLED = booleanPreferencesKey("self_verify_enabled")
        val LIVE_VOICE_READOUT_ENABLED = booleanPreferencesKey("live_voice_readout_enabled")
        val COUNCIL_MODE_ENABLED = booleanPreferencesKey("council_mode_enabled")
        val WORKBENCH_TEMPERATURE = floatPreferencesKey("workbench_temperature")
        val WORKBENCH_TOP_P = floatPreferencesKey("workbench_top_p")
        val WORKBENCH_MAX_TOKENS = intPreferencesKey("workbench_max_tokens")
        val WORKBENCH_THINKING_BUDGET = stringPreferencesKey("workbench_thinking_budget")
        val JSON_SCHEMA_MODE = booleanPreferencesKey("json_schema_mode")
        val JSON_SCHEMA_TEXT = stringPreferencesKey("json_schema_text")
        val SYSTEM_INSTRUCTION_OVERRIDE = stringPreferencesKey("system_instruction_override")
        val ACTIVE_PROJECT_ID = stringPreferencesKey("active_project_id")
        val AUTONOMOUS_AGENT_ENABLED = booleanPreferencesKey("autonomous_agent_enabled")
        val COMPARE_ARENA_ENABLED = booleanPreferencesKey("compare_arena_enabled")
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

    val appLanguage: Flow<AppLanguage> = context.auraDataStore.data.map { prefs ->
        AppLanguage.fromId(prefs[Keys.APP_LANGUAGE] ?: AppLanguage.AUTO.id)
    }

    val webSearchEnabled: Flow<Boolean> = context.auraDataStore.data.map { prefs ->
        prefs[Keys.WEB_SEARCH_ENABLED] ?: false
    }

    val codeExecutionEnabled: Flow<Boolean> = context.auraDataStore.data.map { prefs ->
        prefs[Keys.CODE_EXECUTION_ENABLED] ?: false
    }

    val vaultRagEnabled: Flow<Boolean> = context.auraDataStore.data.map { prefs ->
        prefs[Keys.VAULT_RAG_ENABLED] ?: true
    }

    val userApiKey: Flow<String> = context.auraDataStore.data.map { prefs ->
        prefs[Keys.USER_API_KEY]?.trim().orEmpty()
    }

    val userGroqApiKey: Flow<String> = context.auraDataStore.data.map { prefs ->
        prefs[Keys.USER_GROQ_API_KEY]?.trim().orEmpty()
    }

    val userExaApiKey: Flow<String> = context.auraDataStore.data.map { prefs ->
        prefs[Keys.USER_EXA_API_KEY]?.trim().orEmpty()
    }

    val userOpenRouterApiKey: Flow<String> = context.auraDataStore.data.map { prefs ->
        prefs[Keys.USER_OPENROUTER_API_KEY]?.trim().orEmpty()
    }

    val userDeepSeekApiKey: Flow<String> = context.auraDataStore.data.map { prefs ->
        prefs[Keys.USER_DEEPSEEK_API_KEY]?.trim().orEmpty()
    }

    val preferFastGroq: Flow<Boolean> = context.auraDataStore.data.map { prefs ->
        prefs[Keys.PREFER_FAST_GROQ] ?: true
    }

    val selfVerifyEnabled: Flow<Boolean> = context.auraDataStore.data.map { prefs ->
        prefs[Keys.SELF_VERIFY_ENABLED] ?: false
    }

    val liveVoiceReadoutEnabled: Flow<Boolean> = context.auraDataStore.data.map { prefs ->
        prefs[Keys.LIVE_VOICE_READOUT_ENABLED] ?: false
    }

    val councilModeEnabled: Flow<Boolean> = context.auraDataStore.data.map { prefs ->
        prefs[Keys.COUNCIL_MODE_ENABLED] ?: false
    }

    val workbenchTemperature: Flow<Float> = context.auraDataStore.data.map { prefs ->
        (prefs[Keys.WORKBENCH_TEMPERATURE] ?: 0.7f).coerceIn(0f, 1.5f)
    }

    val workbenchTopP: Flow<Float> = context.auraDataStore.data.map { prefs ->
        (prefs[Keys.WORKBENCH_TOP_P] ?: 0.95f).coerceIn(0.1f, 1.0f)
    }

    val workbenchMaxTokens: Flow<Int> = context.auraDataStore.data.map { prefs ->
        (prefs[Keys.WORKBENCH_MAX_TOKENS] ?: 8192).coerceIn(512, 65536)
    }

    val workbenchThinkingBudget: Flow<String> = context.auraDataStore.data.map { prefs ->
        prefs[Keys.WORKBENCH_THINKING_BUDGET] ?: "AUTO"
    }

    val jsonSchemaMode: Flow<Boolean> = context.auraDataStore.data.map { prefs ->
        prefs[Keys.JSON_SCHEMA_MODE] ?: false
    }

    val jsonSchemaText: Flow<String> = context.auraDataStore.data.map { prefs ->
        prefs[Keys.JSON_SCHEMA_TEXT].orEmpty()
    }

    val systemInstructionOverride: Flow<String> = context.auraDataStore.data.map { prefs ->
        prefs[Keys.SYSTEM_INSTRUCTION_OVERRIDE].orEmpty()
    }

    val activeProjectId: Flow<String?> = context.auraDataStore.data.map { prefs ->
        prefs[Keys.ACTIVE_PROJECT_ID]?.takeIf { it.isNotBlank() }
    }

    val autonomousAgentEnabled: Flow<Boolean> = context.auraDataStore.data.map { prefs ->
        prefs[Keys.AUTONOMOUS_AGENT_ENABLED] ?: false
    }

    val compareArenaEnabled: Flow<Boolean> = context.auraDataStore.data.map { prefs ->
        prefs[Keys.COMPARE_ARENA_ENABLED] ?: false
    }

    suspend fun getEffectiveApiKeyPool(): List<String> =
        mergeKeyPool(userApiKey.first(), resolveBuildConfigApiKey())

    suspend fun getEffectiveGroqKeyPool(): List<String> =
        mergeKeyPool(userGroqApiKey.first(), resolveBuildConfigGroqApiKey())

    suspend fun getEffectiveExaKeyPool(): List<String> =
        mergeKeyPool(userExaApiKey.first(), resolveBuildConfigExaApiKey())

    suspend fun getEffectiveOpenRouterKeyPool(): List<String> =
        mergeKeyPool(userOpenRouterApiKey.first(), resolveBuildConfigOpenRouterApiKey())

    suspend fun getEffectiveDeepSeekKeyPool(): List<String> =
        mergeKeyPool(userDeepSeekApiKey.first(), resolveBuildConfigDeepSeekApiKey())

    suspend fun getEffectiveApiKey(): String = getEffectiveApiKeyPool().firstOrNull().orEmpty()

    suspend fun getEffectiveGroqApiKey(): String = getEffectiveGroqKeyPool().firstOrNull().orEmpty()

    suspend fun getEffectiveExaApiKey(): String = getEffectiveExaKeyPool().firstOrNull().orEmpty()

    suspend fun getEffectiveOpenRouterApiKey(): String = getEffectiveOpenRouterKeyPool().firstOrNull().orEmpty()

    suspend fun getEffectiveDeepSeekApiKey(): String = getEffectiveDeepSeekKeyPool().firstOrNull().orEmpty()

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

    suspend fun setUserGroqApiKey(apiKey: String) {
        val cleaned = apiKey.trim()
        context.auraDataStore.edit { prefs ->
            if (cleaned.isEmpty()) {
                prefs.remove(Keys.USER_GROQ_API_KEY)
            } else {
                prefs[Keys.USER_GROQ_API_KEY] = cleaned
            }
        }
    }

    suspend fun setUserExaApiKey(apiKey: String) {
        val cleaned = apiKey.trim()
        context.auraDataStore.edit { prefs ->
            if (cleaned.isEmpty()) {
                prefs.remove(Keys.USER_EXA_API_KEY)
            } else {
                prefs[Keys.USER_EXA_API_KEY] = cleaned
            }
        }
    }

    suspend fun setUserOpenRouterApiKey(apiKey: String) {
        val cleaned = apiKey.trim()
        context.auraDataStore.edit { prefs ->
            if (cleaned.isEmpty()) {
                prefs.remove(Keys.USER_OPENROUTER_API_KEY)
            } else {
                prefs[Keys.USER_OPENROUTER_API_KEY] = cleaned
            }
        }
    }

    suspend fun setUserDeepSeekApiKey(apiKey: String) {
        val cleaned = apiKey.trim()
        context.auraDataStore.edit { prefs ->
            if (cleaned.isEmpty()) {
                prefs.remove(Keys.USER_DEEPSEEK_API_KEY)
            } else {
                prefs[Keys.USER_DEEPSEEK_API_KEY] = cleaned
            }
        }
    }

    suspend fun setPreferFastGroq(enabled: Boolean) {
        context.auraDataStore.edit { it[Keys.PREFER_FAST_GROQ] = enabled }
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

    suspend fun setAppLanguage(language: AppLanguage) {
        context.auraDataStore.edit { it[Keys.APP_LANGUAGE] = language.id }
    }

    suspend fun setWebSearchEnabled(enabled: Boolean) {
        context.auraDataStore.edit { it[Keys.WEB_SEARCH_ENABLED] = enabled }
    }

    suspend fun setCodeExecutionEnabled(enabled: Boolean) {
        context.auraDataStore.edit { it[Keys.CODE_EXECUTION_ENABLED] = enabled }
    }

    suspend fun setVaultRagEnabled(enabled: Boolean) {
        context.auraDataStore.edit { it[Keys.VAULT_RAG_ENABLED] = enabled }
    }

    suspend fun setSelfVerifyEnabled(enabled: Boolean) {
        context.auraDataStore.edit { it[Keys.SELF_VERIFY_ENABLED] = enabled }
    }

    suspend fun setLiveVoiceReadoutEnabled(enabled: Boolean) {
        context.auraDataStore.edit { it[Keys.LIVE_VOICE_READOUT_ENABLED] = enabled }
    }

    suspend fun setCouncilModeEnabled(enabled: Boolean) {
        context.auraDataStore.edit { it[Keys.COUNCIL_MODE_ENABLED] = enabled }
    }

    suspend fun setWorkbenchTemperature(value: Float) {
        context.auraDataStore.edit { it[Keys.WORKBENCH_TEMPERATURE] = value.coerceIn(0f, 1.5f) }
    }

    suspend fun setWorkbenchTopP(value: Float) {
        context.auraDataStore.edit { it[Keys.WORKBENCH_TOP_P] = value.coerceIn(0.1f, 1.0f) }
    }

    suspend fun setWorkbenchMaxTokens(value: Int) {
        context.auraDataStore.edit { it[Keys.WORKBENCH_MAX_TOKENS] = value.coerceIn(512, 65536) }
    }

    suspend fun setWorkbenchThinkingBudget(mode: String) {
        val cleaned = if (mode in setOf("AUTO", "OFF", "LIGHT", "DEEP")) mode else "AUTO"
        context.auraDataStore.edit { it[Keys.WORKBENCH_THINKING_BUDGET] = cleaned }
    }

    suspend fun setJsonSchemaMode(enabled: Boolean) {
        context.auraDataStore.edit { it[Keys.JSON_SCHEMA_MODE] = enabled }
    }

    suspend fun setJsonSchemaText(schema: String) {
        context.auraDataStore.edit { it[Keys.JSON_SCHEMA_TEXT] = schema.trim() }
    }

    suspend fun setSystemInstructionOverride(text: String) {
        context.auraDataStore.edit { it[Keys.SYSTEM_INSTRUCTION_OVERRIDE] = text }
    }

    suspend fun resetWorkbenchTuning() {
        context.auraDataStore.edit { prefs ->
            prefs.remove(Keys.WORKBENCH_TEMPERATURE)
            prefs.remove(Keys.WORKBENCH_TOP_P)
            prefs.remove(Keys.WORKBENCH_MAX_TOKENS)
            prefs.remove(Keys.WORKBENCH_THINKING_BUDGET)
            prefs.remove(Keys.JSON_SCHEMA_MODE)
            prefs.remove(Keys.JSON_SCHEMA_TEXT)
            prefs.remove(Keys.SYSTEM_INSTRUCTION_OVERRIDE)
        }
    }

    suspend fun setActiveProjectId(projectId: String?) {
        context.auraDataStore.edit { prefs ->
            if (projectId.isNullOrBlank()) prefs.remove(Keys.ACTIVE_PROJECT_ID)
            else prefs[Keys.ACTIVE_PROJECT_ID] = projectId
        }
    }

    suspend fun setAutonomousAgentEnabled(enabled: Boolean) {
        context.auraDataStore.edit { it[Keys.AUTONOMOUS_AGENT_ENABLED] = enabled }
    }

    suspend fun setCompareArenaEnabled(enabled: Boolean) {
        context.auraDataStore.edit { it[Keys.COMPARE_ARENA_ENABLED] = enabled }
    }

    companion object {
        fun parseApiKeyPool(raw: String): List<String> {
            return raw.split(Regex("[,;\\s\\n\\r]+"))
                .map { it.trim() }
                .filter {
                    it.isNotBlank() &&
                        it != "YOUR_GEMINI_API_KEY" &&
                        it != "YOUR_GROQ_API_KEY" &&
                        it != "YOUR_EXA_API_KEY" &&
                        it != "YOUR_OPENROUTER_API_KEY" &&
                        it != "YOUR_DEEPSEEK_API_KEY" &&
                        it != "MY_NEW_API_KEY_DEFAULT_VALUE"
                }
                .distinct()
        }

        fun mergeKeyPool(savedRaw: String, builtInRaw: String): List<String> {
            return (parseApiKeyPool(savedRaw) + parseApiKeyPool(builtInRaw)).distinct()
        }
        fun resolveBuildConfigApiKey(): String {
            val builtIn = BuildConfig.GEMINI_API_KEY.trim()
            return if (builtIn.isBlank() ||
                builtIn == "YOUR_GEMINI_API_KEY" ||
                builtIn == "MY_NEW_API_KEY_DEFAULT_VALUE"
            ) "" else builtIn
        }

        fun resolveBuildConfigGroqApiKey(): String {
            val builtIn = BuildConfig.GROQ_API_KEY.trim()
            return if (builtIn.isBlank() ||
                builtIn == "YOUR_GROQ_API_KEY" ||
                builtIn == "MY_NEW_API_KEY_DEFAULT_VALUE"
            ) "" else builtIn
        }

        fun resolveBuildConfigExaApiKey(): String {
            val builtIn = BuildConfig.EXA_API_KEY.trim()
            return if (builtIn.isBlank() ||
                builtIn == "YOUR_EXA_API_KEY" ||
                builtIn == "MY_NEW_API_KEY_DEFAULT_VALUE"
            ) "" else builtIn
        }

        fun resolveBuildConfigOpenRouterApiKey(): String {
            val builtIn = BuildConfig.OPENROUTER_API_KEY.trim()
            return if (builtIn.isBlank() ||
                builtIn == "YOUR_OPENROUTER_API_KEY" ||
                builtIn == "MY_NEW_API_KEY_DEFAULT_VALUE"
            ) "" else builtIn
        }

        fun resolveBuildConfigDeepSeekApiKey(): String {
            val builtIn = BuildConfig.DEEPSEEK_API_KEY.trim()
            return if (builtIn.isBlank() ||
                builtIn == "YOUR_DEEPSEEK_API_KEY" ||
                builtIn == "MY_NEW_API_KEY_DEFAULT_VALUE"
            ) "" else builtIn
        }
    }
}

package com.example.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.data.model.AiModelTier
import com.example.data.model.CapabilityMode
import com.example.data.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.auraDataStore: DataStore<Preferences> by preferencesDataStore(name = "aura_prefs")

class PreferencesManager(private val context: Context) {
    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DEFAULT_MODEL = stringPreferencesKey("default_model")
        val DEFAULT_CAPABILITY = stringPreferencesKey("default_capability")
        val ACTIVE_PERSONA = stringPreferencesKey("active_persona")
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
}

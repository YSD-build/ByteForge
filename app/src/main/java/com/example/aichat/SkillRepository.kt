package com.example.aichat

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.skillStore by preferencesDataStore(name = "agentforge_skills")

class SkillRepository(private val context: Context) {

    private val store = context.applicationContext.skillStore
    private val gson = Gson()
    private val listType = object : TypeToken<List<Skill>>() {}.type

    val skills: Flow<List<Skill>> = store.data.map { prefs ->
        val raw = prefs[KEY_SKILLS] ?: return@map emptyList<Skill>()
        runCatching { gson.fromJson<List<Skill>>(raw, listType) }.getOrDefault(emptyList())
    }

    suspend fun getSkills(): List<Skill> = skills.first()

    suspend fun saveSkills(list: List<Skill>) {
        store.edit { it[KEY_SKILLS] = gson.toJson(list) }
    }

    suspend fun installSkill(skill: Skill) {
        val list = getSkills().toMutableList()
        val idx = list.indexOfFirst { it.id == skill.id }
        if (idx >= 0) {
            list[idx] = skill.copy(installed = true, enabled = true)
        } else {
            list.add(skill.copy(installed = true, enabled = true))
        }
        saveSkills(list)
    }

    suspend fun toggleSkill(id: String, enabled: Boolean) {
        val list = getSkills().toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx >= 0) list[idx] = list[idx].copy(enabled = enabled)
        saveSkills(list)
    }

    suspend fun removeSkill(id: String) {
        val list = getSkills().toMutableList()
        list.removeAll { it.id == id }
        saveSkills(list)
    }

    companion object {
        private val KEY_SKILLS = stringPreferencesKey("installed_skills")
    }
}

package com.tiktokassist.utils

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.tiktokassist.model.TaskConfig

object PrefsManager {

    private const val PREFS_NAME = "tiktok_assistant_prefs"
    private const val KEY_TASK_CONFIG = "task_config"
    private val gson = Gson()

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveConfig(context: Context, config: TaskConfig) {
        prefs(context).edit()
            .putString(KEY_TASK_CONFIG, gson.toJson(config))
            .apply()
    }

    fun loadConfig(context: Context): TaskConfig {
        val json = prefs(context).getString(KEY_TASK_CONFIG, null)
        return if (json != null) {
            try {
                gson.fromJson(json, TaskConfig::class.java) ?: TaskConfig()
            } catch (e: Exception) {
                TaskConfig()
            }
        } else {
            TaskConfig()
        }
    }

    fun saveStringList(context: Context, key: String, list: List<String>) {
        prefs(context).edit()
            .putString(key, gson.toJson(list))
            .apply()
    }

    fun loadStringList(context: Context, key: String): MutableList<String> {
        val json = prefs(context).getString(key, null) ?: return mutableListOf()
        val type = object : TypeToken<MutableList<String>>() {}.type
        return try {
            gson.fromJson(json, type) ?: mutableListOf()
        } catch (e: Exception) {
            mutableListOf()
        }
    }
}

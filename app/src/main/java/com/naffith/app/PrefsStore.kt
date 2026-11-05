package com.naffith.app

import android.content.SharedPreferences
import com.naffith.app.core.ChoiceMemory

/** تخزين اختيارات المستخدم في SharedPreferences. */
class PrefsStore(private val prefs: SharedPreferences) : ChoiceMemory.Store {
    override fun get(key: String): String? = prefs.getString(key, null)
    override fun put(key: String, value: String) = prefs.edit().putString(key, value).apply()
    override fun remove(key: String) = prefs.edit().remove(key).apply()
    override fun clear() = prefs.edit().clear().apply()
    override fun all(): Map<String, String> =
        prefs.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()
}

package com.naffith.app

import android.content.Context
import com.naffith.app.core.Shortcut
import com.naffith.app.core.ShortcutType
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** يحفظ اختصارات المستخدم في الجوال. */
class ShortcutStore(context: Context) {

    private val prefs = context.getSharedPreferences("shortcuts", Context.MODE_PRIVATE)

    fun all(): List<Shortcut> = runCatching {
        val arr = JSONArray(prefs.getString(KEY, "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val triggers = o.getJSONArray("triggers").let { t -> (0 until t.length()).map { t.getString(it) } }
            Shortcut(
                id = o.getString("id"),
                triggers = triggers,
                type = ShortcutType.valueOf(o.getString("type")),
                value = o.getString("value"),
                title = o.optString("title"),
            )
        }
    }.getOrDefault(emptyList())

    fun save(shortcut: Shortcut) {
        val list = all().filterNot { it.id == shortcut.id } + shortcut
        write(list)
    }

    fun delete(id: String) = write(all().filterNot { it.id == id })

    fun newId(): String = UUID.randomUUID().toString()

    private fun write(list: List<Shortcut>) {
        val arr = JSONArray()
        for (s in list) {
            arr.put(
                JSONObject()
                    .put("id", s.id)
                    .put("triggers", JSONArray(s.triggers))
                    .put("type", s.type.name)
                    .put("value", s.value)
                    .put("title", s.title)
            )
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private companion object {
        const val KEY = "list"
    }
}

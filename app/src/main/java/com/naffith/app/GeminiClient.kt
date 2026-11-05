package com.naffith.app

import android.content.Context
import com.naffith.app.core.ChatText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * السوالف مع Gemini: يرسل الكلام (مع اللي قبله في نفس الجلسة) لـ Gemini API بمفتاح المستخدم،
 * ويرجع الرد. المفتاح ينحفظ في الجوال بس (الإعدادات ← السوالف).
 */
object GeminiClient {

    private const val PREFS = "settings"
    private const val KEY_API = "gemini_api_key"
    private const val KEY_MODEL = "gemini_model"
    private const val KEY_ENABLED = "chat_enabled"
    const val DEFAULT_MODEL = "gemini-flash-latest"

    private const val SYSTEM_PROMPT =
        "أنت «نفّذ»، مساعد صوتي سعودي ودود في جوال المستخدم. رد باللهجة السعودية البيضاء، " +
            "بجمل قصيرة وطبيعية تنقال بالصوت (من جملة إلى ثلاث جمل)، بدون تنسيق ولا رموز ولا قوائم ولا إيموجي. " +
            "إذا طلب شي يحتاج تطبيق (اتصال، واتساب، خرائط، يوتيوب) قل له يقولها كأمر مثل: «اتصل على فلان»."

    class ChatError(val userMessage: Int) : Exception()

    data class Turn(val fromUser: Boolean, val text: String)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun apiKey(context: Context): String = prefs(context).getString(KEY_API, "").orEmpty()
    fun setApiKey(context: Context, key: String) = prefs(context).edit().putString(KEY_API, key.trim()).apply()

    fun model(context: Context): String =
        prefs(context).getString(KEY_MODEL, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL
    fun setModel(context: Context, model: String) = prefs(context).edit().putString(KEY_MODEL, model.trim()).apply()

    fun isEnabled(context: Context) = prefs(context).getBoolean(KEY_ENABLED, true) && apiKey(context).isNotBlank()
    fun enabledSetting(context: Context) = prefs(context).getBoolean(KEY_ENABLED, true)
    fun setEnabled(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()

    /** يرسل [history] (آخرها كلام المستخدم) ويرجع رد Gemini جاهز للنطق. */
    suspend fun reply(context: Context, history: List<Turn>): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val key = apiKey(context)
            if (key.isBlank()) throw ChatError(R.string.chat_no_key)
            val model = URLEncoder.encode(model(context), "UTF-8")
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")

            val contents = JSONArray()
            for (turn in history.takeLast(20)) {
                contents.put(
                    JSONObject()
                        .put("role", if (turn.fromUser) "user" else "model")
                        .put("parts", JSONArray().put(JSONObject().put("text", turn.text)))
                )
            }
            val body = JSONObject()
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM_PROMPT))))
                .put("contents", contents)
                .put("generationConfig", JSONObject().put("maxOutputTokens", 400).put("temperature", 0.8))

            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 30_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("x-goog-api-key", key)
            }
            try {
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                if (code != 200) {
                    throw ChatError(
                        when (code) {
                            400, 401, 403 -> R.string.chat_bad_key
                            404 -> R.string.chat_bad_model
                            429 -> R.string.chat_quota
                            else -> R.string.chat_failed
                        }
                    )
                }
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val parts = json.optJSONArray("candidates")?.optJSONObject(0)
                    ?.optJSONObject("content")?.optJSONArray("parts") ?: throw ChatError(R.string.chat_failed)
                val text = buildString {
                    for (i in 0 until parts.length()) append(parts.optJSONObject(i)?.optString("text").orEmpty())
                }
                ChatText.forSpeech(text).ifBlank { throw ChatError(R.string.chat_failed) }
            } finally {
                conn.disconnect()
            }
        }.recoverCatching { e ->
            if (e is ChatError) throw e
            throw ChatError(R.string.chat_network)
        }
    }
}

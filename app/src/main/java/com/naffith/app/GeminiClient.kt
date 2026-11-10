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
    private const val KEY_ANSWER_MODE = "answer_mode"
    const val DEFAULT_MODEL = "gemini-flash-latest"

    private const val SYSTEM_PROMPT =
        "أنت «مرسال»، مساعد صوتي سعودي ودود في جوال المستخدم. رد باللهجة السعودية البيضاء، " +
            "بجمل قصيرة وطبيعية تنقال بالصوت (من جملة إلى ثلاث جمل)، بدون تنسيق ولا رموز ولا قوائم ولا إيموجي. " +
            "إذا طلب شي يحتاج تطبيق (اتصال، واتساب، خرائط، يوتيوب) قل له يقولها كأمر مثل: «اتصل على فلان»."

    /** [detail] سبب تقني قصير (رمز HTTP أو سبب التوقف) نعرضه عشان نعرف وش صار. */
    private const val TRANSCRIBE_PROMPT =
        "اكتب حرفياً الكلام المنطوق في هذا التسجيل (غالباً لهجة سعودية، وفيه أسماء أشخاص وتطبيقات وأماكن). " +
            "اكتب النص فقط كما انقال، بدون ترجمة ولا شرح ولا علامات تنصيص. إذا ما فيه كلام واضح اكتب -"

    class ChatError(val userMessage: Int, val detail: String = "", val code: Int = 0) : Exception(detail)

    /** موديلات بديلة لما يكون الموديل الأساسي مضغوط. */
    private val FALLBACK_MODELS = listOf("gemini-flash-lite-latest", "gemini-2.5-flash", "gemini-2.5-flash-lite")
    private val RETRYABLE = setOf(404, 429, 500, 502, 503, 504)

    data class Turn(val fromUser: Boolean, val text: String)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun apiKey(context: Context): String = prefs(context).getString(KEY_API, "").orEmpty()
    fun setApiKey(context: Context, key: String) = prefs(context).edit().putString(KEY_API, key.trim()).apply()

    fun model(context: Context): String =
        prefs(context).getString(KEY_MODEL, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL
    fun setModel(context: Context, model: String) = prefs(context).edit().putString(KEY_MODEL, model.trim()).apply()

    /** مين يجاوب على الكلام اللي مو أمر (سؤال/سالفة). */
    enum class AnswerMode(val key: String) {
        /** Gemini داخل مرسال إذا فيه مفتاح، وإلا تطبيق Gemini، وإلا بحث Google. */
        AUTO("auto"),
        IN_APP("in_app"),
        GEMINI_APP("gemini_app"),
        GOOGLE("google"),
        /** يسأل وش تقصد (خرائط، يوتيوب، اتصال…). */
        ASK("ask"),
    }

    fun answerMode(context: Context): AnswerMode {
        val saved = prefs(context).getString(KEY_ANSWER_MODE, null)
        return AnswerMode.entries.firstOrNull { it.key == saved }
            // من نسخة قبل: إذا طفّى السوالف = يسأل وش تقصد.
            ?: if (!prefs(context).getBoolean(KEY_ENABLED, true)) AnswerMode.ASK else AnswerMode.AUTO
    }

    fun setAnswerMode(context: Context, mode: AnswerMode) =
        prefs(context).edit().putString(KEY_ANSWER_MODE, mode.key).apply()

    /** الطريقة الفعلية بعد ما نحل «تلقائي». */
    fun effectiveMode(context: Context, geminiAppInstalled: Boolean): AnswerMode = when (val m = answerMode(context)) {
        AnswerMode.AUTO -> when {
            apiKey(context).isNotBlank() -> AnswerMode.IN_APP
            geminiAppInstalled -> AnswerMode.GEMINI_APP
            else -> AnswerMode.GOOGLE
        }
        else -> m
    }

    /**
     * يرسل [history] (آخرها كلام المستخدم) ويرجع رد Gemini جاهز للنطق.
     * [imageJpeg] صورة الشاشة لـ«اسأل عن الشاشة» (تنضاف لآخر رسالة).
     */
    suspend fun reply(context: Context, history: List<Turn>, imageJpeg: ByteArray? = null): Result<String> =
        withContext(Dispatchers.IO) {
        runCatching {
            val key = apiKey(context)
            if (key.isBlank()) throw ChatError(R.string.chat_no_key)
            val contents = JSONArray()
            val turns = history.takeLast(20)
            turns.forEachIndexed { i, turn ->
                val parts = JSONArray().put(JSONObject().put("text", turn.text))
                if (imageJpeg != null && i == turns.lastIndex && turn.fromUser) {
                    parts.put(
                        JSONObject().put(
                            "inlineData",
                            JSONObject()
                                .put("mimeType", "image/jpeg")
                                .put("data", android.util.Base64.encodeToString(imageJpeg, android.util.Base64.NO_WRAP))
                        )
                    )
                }
                contents.put(
                    JSONObject()
                        .put("role", if (turn.fromUser) "user" else "model")
                        .put("parts", parts)
                )
            }
            // ما نحط حد صغير للطول: موديلات Gemini الحديثة «تفكر» قبل الرد، والتفكير ينحسب من الحد،
            // فلو الحد صغير يخلص في التفكير ويرجع الرد فاضي. القِصَر نطلبه في التعليمات.
            val body = JSONObject()
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM_PROMPT))))
                .put("contents", contents)
                .put("generationConfig", JSONObject().put("maxOutputTokens", 4096).put("temperature", 0.8))
                .toString()

            generate(context, key, body)
        }.recoverCatching { e ->
            if (e is ChatError) throw e
            throw ChatError(R.string.chat_network, e.javaClass.simpleName)
        }
    }

    /**
     * يرسل الطلب لـ Gemini. إذا الموديل مضغوط (503) أو خلص حده (429) أو مو موجود (404)، نجرّب
     * موديل ثاني تلقائياً، وكل موديل نعطيه محاولتين.
     */
    private fun generate(context: Context, key: String, body: String): String {
        val models = (listOf(model(context)) + FALLBACK_MODELS).distinct()
        var lastError: ChatError? = null
        for (m in models) {
            val url = URL(
                "https://generativelanguage.googleapis.com/v1beta/models/" +
                    URLEncoder.encode(m, "UTF-8") + ":generateContent"
            )
            for (attempt in 0 until 2) {
                try {
                    return send(url, key, body)
                } catch (e: ChatError) {
                    lastError = e
                    if (e.code !in RETRYABLE) throw e
                    if (e.code == 404 || e.code == 429) break // ننتقل للموديل اللي بعده على طول
                    Thread.sleep(800)
                }
            }
        }
        throw lastError?.let { if (it.code in 500..599) ChatError(R.string.chat_busy, it.detail, it.code) else it }
            ?: ChatError(R.string.chat_failed)
    }

    /**
     * يحوّل تسجيل صوتي (WAV) لنص عن طريق Gemini. نستخدمه لما محرك التعرف في الجوال ما يسمع
     * (مثلاً والجوال مقفول). يرجع نص فاضي إذا ما فيه كلام.
     */
    suspend fun transcribe(context: Context, wav: ByteArray): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val key = apiKey(context)
            if (key.isBlank()) throw ChatError(R.string.chat_no_key)
            val parts = JSONArray()
                .put(JSONObject().put("text", TRANSCRIBE_PROMPT))
                .put(
                    JSONObject().put(
                        "inlineData",
                        JSONObject()
                            .put("mimeType", "audio/wav")
                            .put("data", android.util.Base64.encodeToString(wav, android.util.Base64.NO_WRAP))
                    )
                )
            val body = JSONObject()
                .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
                .put("generationConfig", JSONObject().put("maxOutputTokens", 2048).put("temperature", 0.0))
                .toString()
            try {
                generate(context, key, body).trim().trim('"', '«', '»').takeUnless { it == "-" }.orEmpty()
            } catch (e: ChatError) {
                // «ما فيه كلام» يرجع أحياناً رد فاضي؛ نعتبره ما سمعنا شي.
                if (e.detail == "empty") "" else throw e
            }
        }.recoverCatching { e ->
            if (e is ChatError) throw e
            throw ChatError(R.string.chat_network, e.javaClass.simpleName)
        }
    }

    private fun send(url: URL, key: String, body: String): String {
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 45_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("x-goog-api-key", key)
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code != 200) {
                val err = runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty()
                val apiMessage = runCatching { JSONObject(err).optJSONObject("error")?.optString("message") }.getOrNull()
                    .orEmpty().take(120)
                throw ChatError(
                    when {
                        code == 400 && apiMessage.contains("API key", ignoreCase = true) -> R.string.chat_bad_key
                        code == 401 || code == 403 -> R.string.chat_bad_key
                        code == 404 -> R.string.chat_bad_model
                        code == 429 -> R.string.chat_quota
                        else -> R.string.chat_failed
                    },
                    if (apiMessage.isBlank()) code.toString() else "$code: $apiMessage",
                    code
                )
            }
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val candidate = json.optJSONArray("candidates")?.optJSONObject(0)
            val parts = candidate?.optJSONObject("content")?.optJSONArray("parts")
            val text = buildString {
                if (parts != null) for (i in 0 until parts.length()) {
                    val part = parts.optJSONObject(i) ?: continue
                    if (part.optBoolean("thought", false)) continue // أفكار الموديل الداخلية مو للنطق
                    append(part.optString("text"))
                }
            }
            val clean = ChatText.forSpeech(text)
            if (clean.isBlank()) {
                val reason = candidate?.optString("finishReason").orEmpty()
                    .ifBlank { json.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty() }
                throw ChatError(R.string.chat_failed, reason.ifBlank { "empty" })
            }
            return clean
        } finally {
            conn.disconnect()
        }
    }
}

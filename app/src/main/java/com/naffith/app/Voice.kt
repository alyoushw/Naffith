package com.naffith.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale

/** إعدادات الصوت المشتركة بين الشاشات: صوت الرد، وحساسية «يا نفّذ». */
object VoicePrefs {
    private const val PREFS = "settings"
    private const val KEY_VOICE = "tts_voice"
    private const val KEY_SENSITIVITY = "wake_sensitivity"
    private const val KEY_SPEAK = "speak_replies"

    /** يرد بالصوت («هلا بيك، أمرني» والأسئلة)؟ إذا لا، المساعد يسمع على طول بدون كلام. */
    fun speakReplies(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SPEAK, true)

    fun setSpeakReplies(context: Context, value: Boolean) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SPEAK, value).apply()

    /** 0 = عادي، 1 = عالي، 2 = عالي جداً. */
    fun sensitivity(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_SENSITIVITY, 1)

    fun setSensitivity(context: Context, value: Int) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_SENSITIVITY, value).apply()

    /** حد الالتقاط لمحرك كلمة التنبيه: كل ما قل التقط أسهل (ومعه احتمال تشغيل بالغلط). */
    fun wakeThreshold(context: Context): Float = when (sensitivity(context)) {
        0 -> 0.25f
        2 -> 0.08f
        else -> 0.15f
    }

    fun voiceName(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_VOICE, null)

    fun setVoiceName(context: Context, name: String?) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_VOICE, name).apply()

    /** الأصوات العربية المتوفرة، الأفضل جودة أول (السعودي/الخليجي قبل غيره). */
    fun arabicVoices(tts: TextToSpeech): List<Voice> =
        runCatching { tts.voices.orEmpty() }.getOrDefault(emptySet())
            .filter { it.locale.language == "ar" && !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
            .sortedWith(
                compareByDescending<Voice> { it.quality }
                    .thenByDescending { it.locale.country == "SA" }
                    .thenBy { it.name }
            )

    /** نختار الصوت اللي اختاره المستخدم، وإلا أعلى جودة عربي. */
    fun applyBestVoice(context: Context, tts: TextToSpeech) {
        val voices = arabicVoices(tts)
        val chosen = voiceName(context)?.let { name -> voices.firstOrNull { it.name == name } } ?: voices.firstOrNull()
        if (chosen != null) {
            runCatching { tts.voice = chosen }
        } else {
            val sa = tts.setLanguage(Locale("ar", "SA"))
            if (sa == TextToSpeech.LANG_MISSING_DATA || sa == TextToSpeech.LANG_NOT_SUPPORTED) tts.setLanguage(Locale("ar"))
        }
        tts.setSpeechRate(0.95f)
    }

    private const val GOOGLE_APP = "com.google.android.googlequicksearchbox"
    private val GOOGLE_RECOGNIZER =
        ComponentName(GOOGLE_APP, "com.google.android.voicesearch.serviceapi.GoogleRecognitionService")

    /**
     * محركات التعرف على الكلام بالترتيب اللي نجرّبه. null = محرك النظام الافتراضي.
     *
     * - محرك النظام الافتراضي أول (هو اللي كان شغال عندك)، إلا إذا صار «نفّذ» نفسه
     *   (لما يكون المساعد الافتراضي) عشان ما نكلّم نفسنا.
     * - بعده محرك تطبيق Google (يدعم العربي السعودي)، وبعدها الباقي.
     * - محركات «على الجهاز فقط» (ذكاء النظام/تحويل النص لكلام) آخر شي لأنها غالباً بدون عربي.
     * إذا محرك رد إن اللغة غير مدعومة، ننتقل للي بعده تلقائياً.
     */
    fun recognizerCandidates(context: Context): List<ComponentName?> {
        val installed = context.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .map { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
            .filter { it.packageName != context.packageName }
        val systemDefault = Settings.Secure.getString(context.contentResolver, "voice_recognition_service")
            ?.let { ComponentName.unflattenFromString(it) }
        val result = mutableListOf<ComponentName?>()
        if (systemDefault != null && systemDefault.packageName != context.packageName) result.add(null)
        if (GOOGLE_RECOGNIZER in installed) result.add(GOOGLE_RECOGNIZER)
        val onDeviceOnly = setOf("com.google.android.as", "com.google.android.tts")
        installed.filter { it !in result && it.packageName == GOOGLE_APP }.forEach { result.add(it) }
        installed.filter { it !in result && it.packageName !in onDeviceOnly }.forEach { result.add(it) }
        installed.filter { it !in result }.forEach { result.add(it) }
        if (result.isEmpty()) result.add(null)
        return result.distinct()
    }

    fun createRecognizer(context: Context, index: Int = 0): SpeechRecognizer {
        val candidates = recognizerCandidates(context)
        val component = candidates.getOrNull(index) ?: candidates.firstOrNull()
        return if (component != null) SpeechRecognizer.createSpeechRecognizer(context, component)
        else SpeechRecognizer.createSpeechRecognizer(context)
    }

    /** فيه محرك تعرف غير «نفّذ» نفسه؟ */
    fun isRecognitionAvailable(context: Context): Boolean =
        context.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .any { it.serviceInfo.packageName != context.packageName }
}

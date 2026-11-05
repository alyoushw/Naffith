package com.naffith.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
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

    /**
     * محرك التعرف على الكلام. إذا «نفّذ» صار المساعد الافتراضي، النظام ممكن يعتبره محرك التعرف
     * الافتراضي، فنختار محرك Google (أو أي محرك ثاني) بالاسم عشان ما نكلّم نفسنا.
     */
    fun createRecognizer(context: Context): SpeechRecognizer {
        val services = context.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .map { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
            .filter { it.packageName != context.packageName }
        val preferred = services.firstOrNull { it.packageName == "com.google.android.googlequicksearchbox" }
            ?: services.firstOrNull { it.packageName.startsWith("com.google.") }
            ?: services.firstOrNull()
        return if (preferred != null) SpeechRecognizer.createSpeechRecognizer(context, preferred)
        else SpeechRecognizer.createSpeechRecognizer(context)
    }

    /** فيه محرك تعرف غير «نفّذ» نفسه؟ */
    fun isRecognitionAvailable(context: Context): Boolean =
        context.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .any { it.serviceInfo.packageName != context.packageName }
}

package com.naffith.app

import android.os.Bundle
import android.view.View

/**
 * نافذة المساعد الصغيرة: تطلع من تحت لما تنادي «يا مرسال» أو تضغط زر المساعد.
 * ترد «هلا بيك، أمرني» بالصوت، تسمع الأمر، تنفذه، وتسكّر. الكرة تتحرك مع صوتك.
 */
class VoicePopupActivity : MainActivity() {

    override val isPopup = true
    override val layoutRes = R.layout.activity_voice_popup

    companion object {
        /** انفتحت من زر المساعد (فيه احتمال لقطة شاشة لـ«اسأل عن الشاشة»). */
        const val EXTRA_FROM_ASSISTANT = "com.naffith.app.extra.FROM_ASSISTANT"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // «اسأل عن الشاشة» يحتاج لقطة الشاشة اللي يعطيها النظام للمساعد الافتراضي بس.
        updateScreenButton(intent)

        // الضغط برا البطاقة يسكّر النافذة.
        findViewById<View>(R.id.popupRoot).setOnClickListener { finish() }
        findViewById<View>(R.id.popupCard).setOnClickListener { }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        updateScreenButton(intent)
    }

    private fun updateScreenButton(intent: android.content.Intent?) {
        val fromAssistant = intent?.getBooleanExtra(EXTRA_FROM_ASSISTANT, false) == true
        findViewById<View>(R.id.screenButton).visibility = if (fromAssistant) View.VISIBLE else View.GONE
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, android.R.anim.fade_out)
    }
}

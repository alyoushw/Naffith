package com.naffith.app

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.os.Bundle
import android.view.View

/**
 * نافذة المساعد الصغيرة: تطلع من تحت لما تنادي «يا نفّذ» أو تضغط مطوّل على زر التشغيل
 * (إذا «نفّذ» هو المساعد الافتراضي). ترد «هلا بيك، أمرني» بالصوت، تسمع الأمر، تنفذه، وتسكّر.
 */
class VoicePopupActivity : MainActivity() {

    override val isPopup = true
    override val layoutRes = R.layout.activity_voice_popup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // الضغط برا البطاقة يسكّر النافذة.
        findViewById<View>(R.id.popupRoot).setOnClickListener { finish() }
        findViewById<View>(R.id.popupCard).setOnClickListener { }

        // نبض خفيف حول الصورة عشان تحس إن فيه أحد يسمعك.
        val halo = findViewById<View>(R.id.avatarHalo)
        listOf("scaleX", "scaleY").forEach { prop ->
            // نكبّر ونصغّر داخل حدود الإطار عشان الهالة ما تنقص من الأطراف.
            ObjectAnimator.ofFloat(halo, prop, 0.82f, 1f).apply {
                duration = 900
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                start()
            }
        }
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, android.R.anim.fade_out)
    }
}

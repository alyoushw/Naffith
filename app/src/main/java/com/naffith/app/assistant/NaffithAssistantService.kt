package com.naffith.app.assistant

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import com.naffith.app.ScreenContext
import com.naffith.app.VoicePopupActivity

/**
 * «مرسال» كمساعد افتراضي للجوال. لما المستخدم يضغط مطوّل على زر التشغيل (أو زر الهوم/إيماءة
 * المساعد)، النظام يفتح جلسة، واحنا نفتح نافذة مرسال الصغيرة اللي ترد «هلا بيك، أمرني».
 * المايك ما يشتغل إلا وقتها. وإذا المستخدم مفعّل «استخدام لقطة الشاشة»، النظام يعطينا صورة
 * من الشاشة نستخدمها لـ«اسأل عن الشاشة».
 */
class NaffithAssistantService : VoiceInteractionService()

class NaffithSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = NaffithSession(this)
}

class NaffithSession(context: Context) : VoiceInteractionSession(context) {

    private val handler = Handler(Looper.getMainLooper())

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        ScreenContext.clear()
        val intent = Intent(context, VoicePopupActivity::class.java)
            .putExtra(VoicePopupActivity.EXTRA_FROM_ASSISTANT, true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startAssistantActivity(intent)
        } else {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        // ننتظر لقطة الشاشة شوي قبل ما نسكّر الجلسة (إذا ما جات نسكّر على أي حال).
        handler.postDelayed({ hide() }, 2000)
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onHandleScreenshot(screenshot: Bitmap?) {
        ScreenContext.set(screenshot)
        handler.removeCallbacksAndMessages(null)
        hide()
    }

    override fun onHide() {
        handler.removeCallbacksAndMessages(null)
        super.onHide()
    }
}

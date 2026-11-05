package com.naffith.app.assistant

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import com.naffith.app.VoicePopupActivity

/**
 * «نفّذ» كمساعد افتراضي للجوال. لما المستخدم يضغط مطوّل على زر التشغيل (أو زر الهوم/إيماءة
 * المساعد)، النظام يفتح جلسة، واحنا نفتح نافذة نفّذ الصغيرة اللي ترد «هلا بيك، أمرني».
 * المايك ما يشتغل إلا وقتها.
 */
class NaffithAssistantService : VoiceInteractionService()

class NaffithSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = NaffithSession(this)
}

class NaffithSession(context: Context) : VoiceInteractionSession(context) {

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val intent = Intent(context, VoicePopupActivity::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startAssistantActivity(intent)
        } else {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        hide()
    }
}

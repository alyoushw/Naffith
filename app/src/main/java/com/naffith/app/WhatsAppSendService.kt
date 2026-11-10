package com.naffith.app

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * خدمة «إمكانية الوصول»: لما يقول المستخدم «ارسل»، نفتح المحادثة والرسالة مكتوبة،
 * وهذي الخدمة تضغط زر الإرسال في واتساب. ما تسوي شي إلا إذا طلبنا إرسال خلال آخر 15 ثانية.
 */
class WhatsAppSendService : AccessibilityService() {

    companion object {
        private const val WINDOW_MS = 15_000L
        private val SEND_IDS = listOf("com.whatsapp:id/send", "com.whatsapp.w4b:id/send")
        private val SEND_LABELS = setOf("send", "إرسال", "ارسال")

        @Volatile
        private var pendingUntil = 0L

        /** الخدمة الشغالة (للتحكم: قفل الشاشة، لقطة الشاشة). */
        @Volatile
        var instance: WhatsAppSendService? = null
            private set

        /** نطلب ضغطة إرسال واحدة في واتساب خلال الثواني الجاية. */
        fun requestSend() {
            pendingUntil = SystemClock.elapsedRealtime() + WINDOW_MS
        }

        fun cancel() {
            pendingUntil = 0L
        }

        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val me = ComponentName(context, WhatsAppSendService::class.java)
            return enabled.split(':').any {
                ComponentName.unflattenFromString(it) == me
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (SystemClock.elapsedRealtime() > pendingUntil) return
        val root = rootInActiveWindow ?: return
        val button = findSendButton(root) ?: return
        if (button.isEnabled && button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            pendingUntil = 0L
        }
    }

    private fun findSendButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (id in SEND_IDS) {
            root.findAccessibilityNodeInfosByViewId(id)?.firstOrNull()?.let { return it }
        }
        // احتياط لو واتساب غيّر المعرّف: نبحث بالوصف.
        for (label in SEND_LABELS) {
            root.findAccessibilityNodeInfosByText(label)
                ?.firstOrNull { it.contentDescription?.toString()?.lowercase() == label && it.isClickable }
                ?.let { return it }
        }
        return null
    }

    override fun onInterrupt() {}
}

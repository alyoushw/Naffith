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

        /** «اتصل على المحل»: لما تفتح صفحة المحل في خرائط Google نضغط «اتصال» (خلال ٢٠ ثانية). */
        @Volatile
        private var mapsCallUntil = 0L
        private const val MAPS = "com.google.android.apps.maps"
        private val CALL_LABELS = setOf("اتصال", "الاتصال", "call", "اتصل")

        fun requestMapsCall() {
            mapsCallUntil = SystemClock.elapsedRealtime() + 20_000L
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
        // «اختصار بخطوات» يعرف متى الشاشة هدأت (خلصت تحميل) عشان يضغط على طول.
        Macro.lastUiChangeAt = SystemClock.uptimeMillis()
        if (SystemClock.elapsedRealtime() <= mapsCallUntil && event?.packageName?.toString() == MAPS) {
            rootInActiveWindow?.let { root -> findCallButton(root)?.let { b ->
                if (b.performAction(AccessibilityNodeInfo.ACTION_CLICK)) mapsCallUntil = 0L
            } }
            return
        }
        if (SystemClock.elapsedRealtime() > pendingUntil) return
        // الخدمة تشوف كل التطبيقات، بس زر الإرسال نضغطه في واتساب بس.
        val pkg = event?.packageName?.toString() ?: return
        if (pkg != "com.whatsapp" && pkg != "com.whatsapp.w4b") return
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

    /** زر «اتصال» في صفحة المحل (النص نفسه «اتصال»، مو اسم محل فيه «اتصالات»). */
    private fun findCallButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val seen = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var count = 0
        while (seen.isNotEmpty() && count < 600) {
            val n = seen.removeFirst()
            count++
            val text = n.text?.toString()?.trim()?.lowercase().orEmpty()
            val desc = n.contentDescription?.toString()?.trim()?.lowercase().orEmpty()
            val isCall = text in CALL_LABELS || desc in CALL_LABELS ||
                CALL_LABELS.any { desc.startsWith("$it ") && desc.length < 40 }
            if (isCall && n.isVisibleToUser) {
                var c: AccessibilityNodeInfo? = n
                var up = 0
                while (c != null && !c.isClickable && up < 4) { c = c.parent; up++ }
                if (c != null && c.isClickable) return c
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { seen.add(it) }
        }
        return null
    }

    override fun onInterrupt() {}
}

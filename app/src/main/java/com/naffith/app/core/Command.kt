package com.naffith.app.core

/** الأوامر التي يفهمها التطبيق. */
sealed interface Command {

    /** فتح الخرائط والبحث عن مكان. [query] فارغ = فتح الخرائط فقط. */
    data class OpenMaps(val query: String, val navigate: Boolean = false) : Command

    /**
     * فتح يوتيوب والبحث فيه. [query] فارغ = فتح يوتيوب فقط.
     * [play] = قال «شغل»: نشغّل أول نتيجة على طول بدل ما نعرض نتائج البحث.
     */
    data class SearchYouTube(val query: String, val play: Boolean = false) : Command

    /**
     * فتح محادثة واتساب. [contactNames] احتمالات لاسم جهة الاتصال مرتبة حسب الأولوية
     * (مثلاً «بخالد» ثم «خالد»). القائمة الفارغة تعني أن الاسم لم يُذكر.
     * [message] نص يُكتب في المحادثة («افتح واتساب روحي اكتب هلا» ← «هلا»).
     * [send] = قال «ارسل»/«وارسلها»: نضغط إرسال تلقائياً.
     * [nameAndMessage] الاسم والرسالة ملتصقين بدون فاصل («اكتب رسالة واتساب محمد الحفة هلا»)؛
     * نفصلهم بعد ما نقرأ جهات الاتصال (انظر [ContactMatcher.splitNameAndMessage]).
     */
    data class WhatsApp(
        val contactNames: List<String>,
        val message: String = "",
        val send: Boolean = false,
        val nameAndMessage: String = "",
    ) : Command

    /** الاتصال بجهة اتصال. نفس منطق [WhatsApp] للأسماء. */
    data class Call(val contactNames: List<String>) : Command

    /**
     * فتح أي تطبيق في الجوال باسمه («افتح التيك توك»)، و[query] بحث داخله إذا طلبه
     * («افتح التيك توك وابحث عن طبخ»).
     */
    data class OpenApp(val appName: String, val query: String = "") : Command

    /** أمر غير مفهوم، مع النص الذي سُمع. */
    data class Unknown(val heard: String) : Command
}

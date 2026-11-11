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

    /**
     * التحكم في الجوال. [value] حسب الأمر: وقت المنبه بالدقائق من منتصف الليل، أو مدة المؤقت
     * بالثواني، أو النسبة للصوت/الإضاءة. [label] رسالة التذكير.
     */
    data class Device(val action: DeviceAction, val value: Int? = null, val label: String = "") : Command

    /** أكثر من أمر في جملة وحدة («حط الجوال صامت وصحني الساعة ٦»). */
    data class Sequence(val steps: List<Command>) : Command

    /** «صور الشاشة وارسلها لريحانة»: لقطة شاشة وترسل بالواتساب. */
    data class SendScreenshot(val contactNames: List<String>) : Command

    /**
     * «بعد دقيقة جهز لي اتصال على روحي» / «ذكرني الساعة ٥ اتصل على أبوي»: في الوقت يطلع إشعار
     * فيه «اتصل» و«إلغاء». [delaySeconds] بعد كم ثانية، أو [atMinutes] الساعة كم (دقائق من منتصف الليل).
     */
    data class CallLater(val contactNames: List<String>, val delaySeconds: Int? = null, val atMinutes: Int? = null) : Command

    /**
     * تذكير من مرسال نفسه: [fullScreen] = شاشة كاملة تطلع على الجهاز (مع تأجيل)، وإلا إشعار فوق.
     * [atMinutes] الوقت بالدقائق من منتصف الليل.
     */
    data class Remind(val label: String, val atMinutes: Int, val fullScreen: Boolean) : Command

    /** أمر غير مفهوم، مع النص الذي سُمع. */
    data class Unknown(val heard: String) : Command
}

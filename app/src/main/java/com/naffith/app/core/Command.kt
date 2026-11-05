package com.naffith.app.core

/** الأوامر التي يفهمها التطبيق. */
sealed interface Command {

    /** فتح الخرائط والبحث عن مكان. [query] فارغ = فتح الخرائط فقط. */
    data class OpenMaps(val query: String) : Command

    /** فتح يوتيوب والبحث فيه. [query] فارغ = فتح يوتيوب فقط. */
    data class SearchYouTube(val query: String) : Command

    /**
     * فتح محادثة واتساب. [contactNames] احتمالات لاسم جهة الاتصال مرتبة حسب الأولوية
     * (مثلاً «بخالد» ثم «خالد»). القائمة الفارغة تعني أن الاسم لم يُذكر.
     */
    data class WhatsApp(val contactNames: List<String>) : Command

    /** الاتصال بجهة اتصال. نفس منطق [WhatsApp] للأسماء. */
    data class Call(val contactNames: List<String>) : Command

    /** أمر غير مفهوم، مع النص الذي سُمع. */
    data class Unknown(val heard: String) : Command
}

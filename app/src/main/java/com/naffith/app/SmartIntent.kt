package com.naffith.app

import android.content.Context
import org.json.JSONObject

/**
 * لما القواعد ما تفهم الكلام (أو فيه «عليه/له» يرجع لشي قبل): نسأل Gemini يحوّله لأوامر
 * مرسال المعروفة، مع آخر اللي سويناه كسياق. مثلاً بعد «افتح العبير للاتصالات في الخرائط»
 * ← «واتصل عليه» يصير: اتصل على مكان «العبير للاتصالات».
 */
object SmartIntent {

    data class Plan(
        val isQuestion: Boolean,
        val commands: List<String>,
        val callPlace: String,
    )

    data class Phone(val number: String, val name: String)

    /** آخر الأوامر (للسياق): «قال: … ← سوّى: …». */
    private val recent = ArrayDeque<String>()

    fun remember(entry: String) {
        recent.addLast(entry)
        while (recent.size > 6) recent.removeFirst()
    }

    fun hasContext() = recent.isNotEmpty()

    private val CATALOG = """
اتصل على <اسم جهة الاتصال>
افتح واتساب <اسم>
ارسل واتساب ل<اسم> <الرسالة>
اكتب واتساب ل<اسم> <الرسالة>   (يكتب بدون إرسال)
ابحث في الخرائط عن <مكان أو نشاط>
وديني <مكان>   (ملاحة)
شغل في يوتيوب <شي>
ابحث في يوتيوب عن <شي>
افتح <اسم التطبيق>
افتح <التطبيق> وابحث عن <شي>
ارفع الصوت / نزل الصوت / الصوت على <0-100> / اكتم الصوت
ارفع الاضاءة / نزل الاضاءة / الاضاءة على <0-100>
شغل الكشاف / طفي الكشاف
حط الجوال صامت / حط الجوال هزاز / طلع الجوال من الصامت
شغل عدم الازعاج / طفي عدم الازعاج
صحني الساعة <الوقت>
ذكرني الساعة <الوقت> <الشي>
ذكرني بعد <المدة> <الشي>
ذكرني الساعة <الوقت> اتصل على <اسم>
ذكرني بعد <المدة> اتصل على <اسم>
مؤقت <المدة>
شغل الاغنية / وقف الاغنية / الاغنية اللي بعدها
كم البطارية / كم الساعة / وش التاريخ
اقفل الشاشة / صور الشاشة
صور الشاشة وارسلها ل<اسم>
انسخ موقعي / ارسل موقعي ل<اسم> / ارسل موقعي لآخر رقم
احفظ آخر رقم باسم <اسم> / اتصل على آخر رقم / ارسل واتساب لآخر رقم <رسالة> / مين آخر واحد اتصل علي
افتح الواي فاي / افتح البلوتوث / افتح اعدادات الموقع
""".trim()

    private fun prompt(text: String): String = buildString {
        appendLine("أنت تحوّل طلب صوتي بالعربي (لهجة سعودية) لأوامر مساعد الجوال «مرسال».")
        appendLine("رد JSON فقط بهذا الشكل: {\"kind\":\"command\" أو \"question\",\"commands\":[...],\"call_place\":\"\"}")
        appendLine("القواعد:")
        appendLine("- commands: كل عملية اكتبها كأمر عربي قصير بنفس صيغ هذي القائمة بالضبط (عبّ <> من كلام المستخدم):")
        appendLine(CATALOG)
        appendLine("- لا تخترع أوامر خارج القائمة. أقصى شي 3 أوامر وبنفس ترتيب كلام المستخدم.")
        appendLine("- الضمائر (عليه، عليها، له، هو، هناك) ترجع للسياق تحت: استبدلها بالاسم أو المكان.")
        appendLine("- إذا يبي يتصل على محل أو شركة أو مكان (مو شخص من جهات اتصاله)، حط اسم المكان (مع المدينة أو الحي لو معروف) في call_place، ولا تحط أمر اتصال له.")
        appendLine("- الأسماء والرسائل اكتبها مثل ما قالها المستخدم.")
        appendLine("- إذا الكلام سؤال أو سالفة أو معلومة (مو شي يتنفذ في الجوال): kind = question و commands فاضية.")
        if (recent.isNotEmpty()) {
            appendLine("السياق (آخر اللي صار، الأحدث تحت):")
            recent.forEach { appendLine("- $it") }
        }
        appendLine("كلام المستخدم: «$text»")
    }

    suspend fun plan(context: Context, text: String): Plan? {
        val raw = GeminiClient.ask(context, prompt(text), json = true).getOrNull() ?: return null
        return runCatching {
            val json = JSONObject(raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1))
            val arr = json.optJSONArray("commands")
            val commands = if (arr == null) emptyList() else (0 until arr.length()).map { arr.optString(it).trim() }.filter { it.isNotEmpty() }
            Plan(
                isQuestion = json.optString("kind") == "question",
                commands = commands.take(3),
                callPlace = json.optString("call_place").trim(),
            )
        }.getOrNull()
    }

    /** رقم مكان أو محل: نبحث في Google عن طريق Gemini. */
    suspend fun findPhone(context: Context, place: String): Phone? {
        val p = buildString {
            appendLine("ابحث في Google عن رقم هاتف هذا المكان في السعودية: «$place».")
            if (recent.isNotEmpty()) appendLine("سياق: ${recent.last()}")
            appendLine("رد بسطر واحد فقط بهذا الشكل بالضبط: PHONE: <الرقم> | NAME: <اسم المكان الرسمي>")
            appendLine("إذا ما لقيت رقم مؤكد رد: PHONE: none")
        }
        val raw = GeminiClient.ask(context, p, search = true).getOrNull() ?: return null
        val phone = Regex("PHONE:\\s*([+0-9٠-٩][0-9٠-٩ \\-]{5,})", RegexOption.IGNORE_CASE).find(raw)?.groupValues?.get(1)
            ?.filter { it.isDigit() || it == '+' }?.map { c -> if (c in '٠'..'٩') '0' + (c - '٠') else c }?.joinToString("")
            ?: return null
        val name = Regex("NAME:\\s*(.+)", RegexOption.IGNORE_CASE).find(raw)?.groupValues?.get(1)?.trim()?.trim('*', ' ')
            ?.takeIf { it.isNotBlank() } ?: place
        return Phone(phone, name)
    }
}

package com.naffith.app

import android.content.Context
import com.naffith.app.core.Command
import com.naffith.app.core.CommandParser
import com.naffith.app.core.DeviceAction
import com.naffith.app.core.LastCallerAction
import org.json.JSONObject

/**
 * عقل مرسال: Gemini يفهم كلام المستخدم الحر (ويصحّح أخطاء السمع) ويرجّع أوامر منظّمة ننفّذها،
 * مع آخر اللي سويناه كسياق. القواعد المحلية ([CommandParser]) تبقى احتياط بدون نت أو مفتاح.
 */
object SmartIntent {

    data class Phone(val number: String, val name: String)

    /** آخر الأوامر (للسياق): «قال: … ← سوّى: …». */
    private val recent = ArrayDeque<String>()

    fun remember(entry: String) {
        recent.addLast(entry)
        while (recent.size > 6) recent.removeFirst()
    }

    fun hasContext() = recent.isNotEmpty()

    /** نتيجة الفهم: أوامر جاهزة للتنفيذ، أو سؤال/سالفة، ومعها الجملة بعد تصحيح السمع. */
    data class Understood(val commands: List<Command>, val isQuestion: Boolean, val text: String, val summary: String)

    private const val DEVICE_PHRASES =
        "ارفع الصوت | نزل الصوت | الصوت على <0-100> | اكتم الصوت | ارفع الاضاءة | نزل الاضاءة | الاضاءة على <0-100> | " +
            "شغل الكشاف | طفي الكشاف | حط الجوال صامت | حط الجوال هزاز | طلع الجوال من الصامت | شغل عدم الازعاج | " +
            "طفي عدم الازعاج | صحني الساعة <الوقت> | مؤقت <المدة> | شغل الاغنية | وقف الاغنية | الاغنية اللي بعدها | " +
            "الاغنية اللي قبلها | كم البطارية | كم الساعة | وش التاريخ | اقفل الشاشة | صور الشاشة | افتح الواي فاي | " +
            "افتح البلوتوث | افتح وضع الطيران | افتح اعدادات الموقع | افتح بيانات الجوال"

    private fun prompt(alternatives: List<String>, nowMinutes: Int): String = buildString {
        appendLine("أنت عقل مساعد صوتي للجوال اسمه «مرسال». المستخدم سعودي ويتكلم بلهجته بحرية، وكلامه واصل من محرك تعرف على الصوت فممكن فيه أخطاء سمع (مثلاً «مفتاح» بدل «افتح»، أو كلمات ملخبطة).")
        appendLine("افهم وش يقصد بالضبط مثل ما يفهمه إنسان، وصحّح أخطاء السمع من المعنى، ورد JSON فقط:")
        appendLine("{\"text\":\"الجملة بعد التصحيح\",\"kind\":\"actions\" أو \"question\",\"actions\":[...]}")
        appendLine("أنواع الـactions (استخدم اللي يلزم بس، لين 3 بترتيب كلامه):")
        appendLine("{\"type\":\"call\",\"contact\":\"اسم الشخص\"}  اتصال بشخص من جهات اتصاله")
        appendLine("{\"type\":\"call_place\",\"place\":\"اسم المحل/الشركة/المطعم\"}  اتصال بمحل أو مكان (مو شخص)")
        appendLine("{\"type\":\"whatsapp\",\"contact\":\"\",\"message\":\"\",\"send\":true/false}  فتح محادثة واتساب (message فاضي = بس افتح المحادثة). send=true إذا قال ارسل، false إذا قال اكتب أو بس افتح")
        appendLine("{\"type\":\"maps_search\",\"query\":\"\"}  بحث عن مكان أو نشاط في الخرائط")
        appendLine("{\"type\":\"navigate\",\"destination\":\"\"}  ملاحة وتوجيه لمكان («البيت» و«الدوام» اكتبها كذا)")
        appendLine("{\"type\":\"youtube\",\"query\":\"\",\"play\":true/false}  play=true إذا يبي يشغّل على طول")
        appendLine("{\"type\":\"open_app\",\"app\":\"اسم التطبيق\",\"query\":\"بحث داخله أو فاضي\"}")
        appendLine("{\"type\":\"remind\",\"text\":\"وش يذكّره فيه\",\"time\":\"HH:MM\" أو \"delay_seconds\":رقم}  تذكير عادي")
        appendLine("{\"type\":\"call_later\",\"contact\":\"\",\"time\":\"HH:MM\" أو \"delay_seconds\":رقم,\"note\":\"السبب إن وجد\"}  تذكير باتصال على شخص")
        appendLine("{\"type\":\"share_location\",\"to\":\"contact\" أو \"last_caller\" أو \"copy\",\"contact\":\"\"}  إرسال أو نسخ موقعه")
        appendLine("{\"type\":\"last_caller\",\"action\":\"save\"|\"whatsapp\"|\"call\"|\"show\",\"name\":\"\",\"message\":\"\"}  آخر رقم في سجل المكالمات")
        appendLine("{\"type\":\"screenshot_send\",\"contact\":\"\"}  يصور الشاشة ويرسلها واتساب")
        appendLine("{\"type\":\"device\",\"phrase\":\"...\"}  تحكم في الجوال؛ phrase لازم وحدة من هذي الصيغ بالضبط: $DEVICE_PHRASES")
        appendLine("قواعد:")
        appendLine("- الوقت الحين ${"%02d:%02d".format(nowMinutes / 60, nowMinutes % 60)}. time بنظام 24 ساعة لأقرب وقت جاي («الساعة 7» والحين العصر = 19:00). «بعد ربع ساعة» = delay_seconds 900.")
        appendLine("- contact: اسم الشخص بس مثل ما قاله (بدون «على/لـ/بـ» وبدون كلمات الأمر). «روحي»، «أمي»، «أبو فهد» أسماء جهات اتصال عادية.")
        appendLine("- الضمائر (عليه، لها، هناك…) ارجع فيها للسياق. إذا «ابحث عن محل… واتصل عليه» فهذا call_place.")
        appendLine("- إذا ناقص شي ضروري (مثلاً ما قال مين) خل الحقل فاضي، والمساعد يسأله.")
        appendLine("- إذا كلامه سؤال أو معلومة أو سالفة (مو شي يتنفذ في الجوال): kind=question و actions فاضية.")
        if (recent.isNotEmpty()) {
            appendLine("السياق (آخر اللي صار، الأحدث تحت):")
            recent.forEach { appendLine("- $it") }
        }
        appendLine("اللي سمعه محرك الصوت (احتمالات، الأول أرجح):")
        alternatives.take(4).forEachIndexed { i, a -> appendLine("${i + 1}. «$a»") }
    }

    /** يفهم كلام المستخدم الحر ويحوّله لأوامر. null = ما قدرنا (بدون نت/مفتاح/رد غلط). */
    suspend fun understand(context: Context, alternatives: List<String>, nowMinutes: Int): Understood? {
        val raw = GeminiClient.ask(context, prompt(alternatives, nowMinutes), json = true, fast = true).getOrNull() ?: return null
        return runCatching {
            val json = JSONObject(raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1))
            val text = json.optString("text").trim().ifBlank { alternatives.first() }
            val arr = json.optJSONArray("actions")
            val commands = mutableListOf<Command>()
            val labels = mutableListOf<String>()
            if (arr != null) for (i in 0 until minOf(arr.length(), 3)) {
                val o = arr.optJSONObject(i) ?: continue
                val cmd = toCommand(o, nowMinutes) ?: continue
                commands += cmd
                labels += label(o)
            }
            Understood(commands, json.optString("kind") == "question" || commands.isEmpty(), text, labels.joinToString(" · "))
        }.getOrNull()
    }

    private fun names(o: JSONObject, key: String = "contact"): List<String> =
        o.optString(key).trim().takeIf { it.isNotEmpty() }?.let { CommandParser.parseName(it).ifEmpty { listOf(it) } } ?: emptyList()

    /** "19:15" ← 1155. */
    private fun minutes(o: JSONObject): Int? {
        val m = Regex("(\\d{1,2})[:.](\\d{2})").find(o.optString("time")) ?: return null
        return ((m.groupValues[1].toInt() % 24) * 60 + m.groupValues[2].toInt() % 60)
    }

    private fun delay(o: JSONObject): Int? = o.optInt("delay_seconds", 0).takeIf { it > 0 }

    private fun toCommand(o: JSONObject, now: Int): Command? = when (o.optString("type")) {
        "call" -> Command.Call(names(o))
        "call_place" -> o.optString("place").trim().takeIf { it.isNotEmpty() }?.let { Command.CallPlace(it) }
        "whatsapp" -> Command.WhatsApp(names(o), o.optString("message").trim(), o.optBoolean("send", false))
        "maps_search" -> Command.OpenMaps(o.optString("query").trim())
        "navigate" -> Command.OpenMaps(o.optString("destination").trim(), navigate = true)
        "youtube" -> Command.SearchYouTube(o.optString("query").trim(), o.optBoolean("play", false))
        "open_app" -> o.optString("app").trim().takeIf { it.isNotEmpty() }?.let { Command.OpenApp(it, o.optString("query").trim()) }
        "remind" -> {
            val at = minutes(o) ?: delay(o)?.let { (now + (it + 59) / 60) % 1440 }
            val text = o.optString("text").trim()
            if (at == null || text.isEmpty()) null else Command.Device(DeviceAction.ALARM, at, text)
        }
        "call_later" -> {
            val at = minutes(o)
            val d = delay(o)
            if (at == null && d == null) Command.Call(names(o))
            else Command.CallLater(names(o), if (at == null) d else null, at, o.optString("note").trim())
        }
        "share_location" -> when (o.optString("to")) {
            "copy" -> Command.ShareLocation(emptyList(), copyOnly = true)
            "last_caller" -> Command.ShareLocation(emptyList(), toLastCaller = true)
            else -> Command.ShareLocation(names(o))
        }
        "last_caller" -> Command.LastCaller(
            when (o.optString("action")) {
                "save" -> LastCallerAction.SAVE
                "whatsapp" -> LastCallerAction.WHATSAPP
                "call" -> LastCallerAction.CALL
                else -> LastCallerAction.SHOW
            },
            name = o.optString("name").trim(),
            message = o.optString("message").trim(),
            send = o.optString("message").isNotBlank(),
        )
        "screenshot_send" -> Command.SendScreenshot(names(o))
        "device" -> CommandParser.parse(o.optString("phrase"), now).takeIf { it is Command.Device }
        else -> null
    }

    /** وصف قصير نعرضه للمستخدم: وش فهمنا. */
    private fun label(o: JSONObject): String = when (o.optString("type")) {
        "call" -> "اتصال: ${o.optString("contact")}"
        "call_place" -> "اتصال بمحل: ${o.optString("place")}"
        "whatsapp" -> "واتساب: ${o.optString("contact")}" + o.optString("message").let { if (it.isBlank()) "" else " «$it»" }
        "maps_search" -> "خرائط: ${o.optString("query")}"
        "navigate" -> "ملاحة: ${o.optString("destination")}"
        "youtube" -> "يوتيوب: ${o.optString("query")}"
        "open_app" -> "فتح ${o.optString("app")}"
        "remind" -> "تذكير: ${o.optString("text")}"
        "call_later" -> "تذكير اتصال: ${o.optString("contact")}"
        "share_location" -> "الموقع"
        "last_caller" -> "آخر رقم"
        "screenshot_send" -> "لقطة شاشة: ${o.optString("contact")}"
        "device" -> o.optString("phrase")
        else -> ""
    }

    /** رقم مكان أو محل: نبحث في Google عن طريق Gemini. */
    suspend fun findPhone(context: Context, place: String, near: String? = null): Phone? {
        val p = buildString {
            appendLine("ابحث في Google (وخرائط Google) عن رقم هاتف هذا المحل أو المكان في السعودية: «$place».")
            if (near != null) appendLine("المستخدم قريب من الإحداثيات $near، فاختر أقرب فرع له.")
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

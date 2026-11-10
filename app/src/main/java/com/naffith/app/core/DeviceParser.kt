package com.naffith.app.core

/** أوامر التحكم في الجوال. */
enum class DeviceAction {
    ALARM, TIMER,
    VOLUME_UP, VOLUME_DOWN, VOLUME_MUTE, VOLUME_MAX, VOLUME_SET,
    RINGER_SILENT, RINGER_VIBRATE, RINGER_NORMAL,
    BRIGHTNESS_UP, BRIGHTNESS_DOWN, BRIGHTNESS_MAX, BRIGHTNESS_SET, BRIGHTNESS_AUTO,
    FLASH_ON, FLASH_OFF,
    MEDIA_PAUSE, MEDIA_PLAY, MEDIA_NEXT, MEDIA_PREVIOUS,
    WIFI, BLUETOOTH, MOBILE_DATA, AIRPLANE, LOCATION,
    DND_ON, DND_OFF,
    BATTERY, TIME, DATE,
    LOCK_SCREEN, SCREENSHOT,
}

/**
 * يفهم أوامر التحكم: «صحني الساعة ٦ ونص»، «ارفع الصوت»، «ولّع الكشاف»، «كم البطارية؟»…
 * [nowMinutes] الوقت الحالي (دقائق من منتصف الليل) عشان «بعد ساعة» ونختار الصبح أو الليل.
 */
object DeviceParser {

    private fun n(s: String) = ArabicText.normalize(s)
    private fun set(vararg w: String) = w.map { n(it) }.toSet()

    // ---------- الكلمات ----------
    private val alarmWords = set("منبه", "المنبه", "منبة", "صحني", "صحيني", "قومني", "قوميني", "نبهني", "صحصحني", "alarm", "ذكرني", "ذكريني")
    private val reminderWords = set("ذكرني", "ذكريني")
    private val timerWords = set("مؤقت", "المؤقت", "تايمر", "التايمر", "عداد", "timer", "ستوب ووتش")

    private val volumeWords = set("الصوت", "صوت", "الفوليوم", "فوليوم", "volume", "الساوند", "الصوتيه")
    private val brightWords = set(
        "الاضاءه", "اضاءه", "الاضاءة", "اضاءة", "السطوع", "سطوع", "الشاشه", "شاشه", "النور", "الضو", "ضو",
        "الاناره", "اناره", "brightness", "الاضائه"
    )
    private val flashWords = set("الكشاف", "كشاف", "الفلاش", "فلاش", "الاتريك", "اتريك", "اللمبه", "flashlight", "torch")
    private val mediaWords = set("الاغنيه", "اغنيه", "الموسيقى", "الموسيقي", "المقطع", "الفيديو", "الصوتيات", "الشيله", "القصيده", "music")

    private val upWords = set(
        "ارفع", "رفع", "علي", "عل", "علّي", "زيد", "زيده", "زود", "كثر", "كبر", "قوي", "اقوى", "اكثر", "اعلى",
        "ضوي", "نور", "شعشع", "اسطع", "ارفعه", "طلع", "up", "increase", "higher", "louder", "brighter"
    )
    private val downWords = set(
        "نزل", "انزل", "وطي", "وط", "قلل", "قل", "اقل", "خفض", "اخفض", "صغر", "طوي", "خفف", "هدي", "اهدى",
        "اضعف", "ضعف", "عتم", "غمق", "ظلم", "خافت", "خافته", "غامق", "down", "decrease", "lower", "dim", "quieter"
    )
    private val onWords = set("شغل", "ولع", "افتح", "فعل", "on")
    private val offWords = set("طف", "طفي", "اطفي", "سكر", "اقفل", "وقف", "الغي", "شيل", "off")
    private val maxWords = set("كامل", "فل", "اخر", "الاخر", "max", "كله")
    private val muteWords = set("اكتم", "كتم", "اسكت", "mute")

    private val silentWords = set("صامت", "الصامت", "silent")
    private val vibrateWords = set("هزاز", "الهزاز", "اهتزاز", "vibrate")
    private val normalRingWords = set("عام", "رنين", "normal")

    private val pauseWords = set("وقف", "وقّف", "ايقاف", "pause", "stop")
    private val resumeWords = set("كمل", "كملها", "استمر", "resume", "play")
    private val nextWords = set("التاليه", "التالي", "بعدها", "next", "skip")
    private val prevWords = set("السابقه", "السابق", "قبلها", "previous")

    private val questionWords = set("كم", "وش", "ايش", "شنو", "قديش", "متى", "how", "what")
    private val batteryWords = set("البطاريه", "بطاريه", "الشحن", "battery")
    private val dateWords = set("التاريخ", "تاريخ", "date")

    private val fillers = listOf("يا مرسال", "لو سمحت", "الله يعافيك", "الله يخليك", "من فضلك")

    fun parse(rawText: String, nowMinutes: Int): Command.Device? {
        // «7:15» ← «7 15»، ونشيل المجاملات.
        var text = rawText.replace(':', ' ')
        for (f in fillers) text = text.replace(f, " ")
        // الكلمة الأصلية جنب نسختها الموحّدة (عشان نص التذكير يطلع بإملائه).
        val orig = text.split(Regex("\\s+")).filter { n(it).isNotEmpty() }
        val words = orig.map { n(it) }
        if (words.isEmpty()) return null
        if (words.isEmpty()) return null
        fun has(s: Set<String>) = words.any { w -> forms(w).any { it in s } }
        fun hasPhrase(p: String) = n(text).contains(n(p))

        // المنبه والتذكير
        if (has(alarmWords)) {
            val label = if (has(reminderWords)) reminderLabel(words, orig) else ""
            val dur = TimeParser.duration(words)
            if (dur != null && hasRelative(words)) return Command.Device(DeviceAction.ALARM, (nowMinutes + dur / 60) % 1440, label)
            TimeParser.clock(words, nowMinutes)?.let { return Command.Device(DeviceAction.ALARM, it, label) }
            if (dur != null) return Command.Device(DeviceAction.ALARM, (nowMinutes + dur / 60) % 1440, label)
            if (!has(reminderWords)) return Command.Device(DeviceAction.ALARM, null, label)
        }
        if (has(timerWords)) return Command.Device(DeviceAction.TIMER, TimeParser.duration(words))

        // أسئلة: البطارية، الوقت، التاريخ
        if (has(batteryWords) && (has(questionWords) || words.size <= 2)) return Command.Device(DeviceAction.BATTERY)
        if (has(questionWords) && words.any { it == "الساعه" || it == "الوقت" } && words.size <= 3) return Command.Device(DeviceAction.TIME)
        if (has(dateWords) && words.size <= 3 && (has(questionWords) || "اليوم" in words)) return Command.Device(DeviceAction.DATE)

        // الرنين: صامت/هزاز/عام
        if (has(silentWords)) {
            val leave = has(offWords) || hasPhrase("من الصامت") || words.any { it == "طلع" || it == "طلعه" }
            return Command.Device(if (leave) DeviceAction.RINGER_NORMAL else DeviceAction.RINGER_SILENT)
        }
        if (has(vibrateWords)) return Command.Device(DeviceAction.RINGER_VIBRATE)

        // الكشاف
        if (has(flashWords)) return Command.Device(if (has(offWords)) DeviceAction.FLASH_OFF else DeviceAction.FLASH_ON)

        // الصوت
        if (has(volumeWords) && !has(brightWords)) {
            val num = TimeParser.number(words)
            return when {
                has(muteWords) || has(offWords) -> Command.Device(DeviceAction.VOLUME_MUTE)
                has(maxWords) -> Command.Device(DeviceAction.VOLUME_MAX)
                num != null -> Command.Device(DeviceAction.VOLUME_SET, percent(num))
                has(downWords) -> Command.Device(DeviceAction.VOLUME_DOWN)
                has(upWords) || has(onWords) -> Command.Device(DeviceAction.VOLUME_UP)
                has(normalRingWords) -> Command.Device(DeviceAction.RINGER_NORMAL)
                else -> null
            }
        }
        if (has(muteWords)) return Command.Device(DeviceAction.VOLUME_MUTE)

        // الشاشة: قفل ولقطة
        if (hasPhrase("صور الشاشه") || hasPhrase("لقطه شاشه") || hasPhrase("سكرين شوت") || hasPhrase("screenshot")) {
            return Command.Device(DeviceAction.SCREENSHOT)
        }
        if ((has(set("اقفل", "قفل", "سكر", "lock")) && (hasPhrase("الشاشه") || hasPhrase("الجوال")))) {
            return Command.Device(DeviceAction.LOCK_SCREEN)
        }

        // الإضاءة
        if (has(brightWords)) {
            val num = TimeParser.number(words)
            return when {
                has(set("تلقائي", "تلقائيه", "اوتوماتيك", "auto")) -> Command.Device(DeviceAction.BRIGHTNESS_AUTO)
                has(maxWords) -> Command.Device(DeviceAction.BRIGHTNESS_MAX)
                num != null -> Command.Device(DeviceAction.BRIGHTNESS_SET, percent(num))
                has(downWords) -> Command.Device(DeviceAction.BRIGHTNESS_DOWN)
                has(upWords) -> Command.Device(DeviceAction.BRIGHTNESS_UP)
                else -> null
            }
        }

        // عدم الإزعاج
        if (hasPhrase("عدم الازعاج") || hasPhrase("لا تزعجني") || hasPhrase("do not disturb")) {
            return Command.Device(if (has(offWords) && !hasPhrase("لا تزعجني")) DeviceAction.DND_OFF else DeviceAction.DND_ON)
        }

        // الاتصالات: نفتح لوحتها (أندرويد ما يسمح بتشغيلها مباشرة)
        if (hasPhrase("واي فاي") || hasPhrase("وايفاي") || has(set("wifi", "الواي"))) return Command.Device(DeviceAction.WIFI)
        if (has(set("البلوتوث", "بلوتوث", "bluetooth"))) return Command.Device(DeviceAction.BLUETOOTH)
        if (has(set("الطيران", "airplane"))) return Command.Device(DeviceAction.AIRPLANE)
        if (has(set("البيانات", "الداتا", "data")) && (has(onWords) || has(offWords))) return Command.Device(DeviceAction.MOBILE_DATA)
        if (hasPhrase("خدمه الموقع") || hasPhrase("خدمات الموقع") || has(set("gps", "الجي بي اس"))) return Command.Device(DeviceAction.LOCATION)

        // الموسيقى: «وقف الأغنية»، «كمل»، «اللي بعدها»
        val shortCmd = words.size <= 4
        if (has(nextWords) && (has(mediaWords) || shortCmd)) return Command.Device(DeviceAction.MEDIA_NEXT)
        if (has(prevWords) && (has(mediaWords) || shortCmd)) return Command.Device(DeviceAction.MEDIA_PREVIOUS)
        if (has(pauseWords) && (has(mediaWords) || words.size <= 2)) return Command.Device(DeviceAction.MEDIA_PAUSE)
        if (has(resumeWords) && (has(mediaWords) || words.size <= 2)) return Command.Device(DeviceAction.MEDIA_PLAY)
        return null
    }

    /** الكلمة وصيغها بدون «و» في أولها وبدون ضمير في آخرها («وقللها» ← «قلل»). */
    private fun forms(w: String): List<String> {
        val base = mutableListOf(w)
        if (w.startsWith("و") && w.length > 2) base += w.drop(1)
        val out = base.toMutableList()
        for (b in base) for (suffix in listOf("ها", "هم", "لي", "ه", "ي")) {
            if (b.length > suffix.length + 2 && b.endsWith(suffix)) out += b.dropLast(suffix.length)
        }
        return out
    }

    /** «بعد ساعة»، «بعد ١٠ دقايق». */
    private fun hasRelative(words: List<String>) = words.any { it == "بعد" }

    /** نص التذكير: اللي بعد الوقت («ذكرني الساعة ٥ أتصل على أبوي» ← «أتصل على أبوي»). */
    private fun reminderLabel(words: List<String>, orig: List<String>): String {
        val skip = set("ذكرني", "ذكريني", "الساعه", "ساعه", "بعد", "على", "عند", "الصبح", "الصباح", "الظهر",
            "العصر", "المغرب", "العشا", "العشاء", "الليل", "بالليل", "مساء", "المسا", "صباحا", "اليوم", "بكره",
            "دقيقه", "دقايق", "دقائق", "ساعات", "ساعتين", "دقيقتين", "ونص", "وربع", "وثلث", "الا", "ربع", "ثلث", "نص", "ان", "اني")
        val start = words.indexOfFirst { !(it in skip || TimeParser.wordNumber(it) != null || it.toIntOrNull() != null) }
        return if (start < 0) "" else orig.drop(start).joinToString(" ")
    }

    /** ٠-١٠ = من عشرة، وأكثر = نسبة مئوية. */
    private fun percent(num: Int) = if (num in 0..10) num * 10 else num.coerceIn(0, 100)
}

/** الأرقام والأوقات بالعربي (كلمات أو أرقام). */
object TimeParser {

    private val words = mapOf(
        "صفر" to 0, "واحد" to 1, "وحده" to 1, "اثنين" to 2, "ثنين" to 2, "اثنتين" to 2, "ثلاث" to 3, "ثلاثه" to 3,
        "اربع" to 4, "اربعه" to 4, "خمس" to 5, "خمسه" to 5, "ست" to 6, "سته" to 6, "سبع" to 7, "سبعه" to 7,
        "ثمان" to 8, "ثمانيه" to 8, "ثمنيه" to 8, "تسع" to 9, "تسعه" to 9, "عشر" to 10, "عشره" to 10,
        "احدعش" to 11, "احدعشر" to 11, "حدعش" to 11, "اثنعش" to 12, "اثنعشر" to 12, "ثنعش" to 12,
        "خمسطعش" to 15, "خمستعش" to 15, "عشرين" to 20, "ثلاثين" to 30, "اربعين" to 40, "خمسين" to 50,
        "ستين" to 60, "سبعين" to 70, "ثمانين" to 80, "تسعين" to 90, "ميه" to 100, "مية" to 100,
    )

    fun wordNumber(w: String): Int? {
        val x = if (w.startsWith("و") && w.length > 2 && w.drop(1) in words) w.drop(1) else w
        return words[x]
    }

    private fun num(w: String): Int? = w.toIntOrNull() ?: wordNumber(w)

    /** أول رقم في الجملة («الصوت على ٥٠» ← 50). */
    fun number(ws: List<String>): Int? = ws.firstNotNullOfOrNull { num(it) }

    /** مدة بالثواني: «١٠ دقايق»، «ساعة ونص»، «نص ساعة»، «ساعتين». */
    fun duration(ws: List<String>): Int? {
        var total = 0
        var found = false
        for ((i, w) in ws.withIndex()) {
            val prev = ws.getOrNull(i - 1)
            val amount = prev?.let { num(it) }
            when (w) {
                "ساعتين" -> { total += 7200; found = true }
                "دقيقتين" -> { total += 120; found = true }
                "ساعه", "ساعات" -> {
                    total += 3600 * (amount ?: if (prev == "نص") 0 else if (prev == "ربع") 0 else 1)
                    if (prev == "نص") total += 1800
                    if (prev == "ربع") total += 900
                    found = true
                }
                "دقيقه", "دقايق", "دقائق", "دقيقة" -> { total += 60 * (amount ?: 1); found = true }
                "ثانيه", "ثواني" -> { total += amount ?: 1; found = true }
                "ونص" -> if (found) total += if (ws.getOrNull(i - 1)?.startsWith("ساع") == true || ws.getOrNull(i - 1) == "ساعتين") 1800 else 30
            }
        }
        return if (found && total > 0) total else null
    }

    /**
     * وقت المنبه (دقائق من منتصف الليل): «الساعة ٦ ونص الصبح»، «٥ العصر»، «٧:١٥».
     * بدون صبح/ليل نختار أقرب وقت جاي.
     */
    fun clock(ws: List<String>, nowMinutes: Int): Int? {
        // نبدأ من بعد «الساعه» إذا موجودة، وإلا أول رقم.
        val start = ws.indexOfFirst { it == "الساعه" || it == "ساعه" }.let { if (it >= 0) it + 1 else 0 }
        val idx = (start until ws.size).firstOrNull { num(ws[it]) != null } ?: return null
        var hour = num(ws[idx]) ?: return null
        if (hour !in 0..23) return null
        var minute = 0
        val next = ws.getOrNull(idx + 1)
        val next2 = ws.getOrNull(idx + 2)
        when {
            next == "ونص" -> minute = 30
            next == "وربع" -> minute = 15
            next == "وثلث" -> minute = 20
            next == "الا" && next2 == "ربع" -> { hour -= 1; minute = 45 }
            next == "الا" && next2 == "ثلث" -> { hour -= 1; minute = 40 }
            next != null && num(next) != null && (num(next) ?: 99) < 60 -> minute = num(next)!!
            next != null && next.startsWith("و") && wordNumber(next) != null -> minute = wordNumber(next)!!
        }
        if (hour < 0) hour += 12
        val all = ws.toSet()
        val morning = setOf("الصبح", "الصباح", "صباحا", "الفجر", "صبح", "am")
        val noon = setOf("الظهر", "ظهر")
        val evening = setOf("العصر", "المغرب", "العشا", "العشاء", "مساء", "المسا", "الليل", "بالليل", "عصر", "مغرب", "pm")
        when {
            all.any { it in morning } -> if (hour == 12) hour = 0
            all.any { it in noon } -> if (hour < 6) hour += 12
            all.any { it in evening } -> if (hour < 12) hour += 12
            hour <= 12 -> {
                // أقرب وقت جاي: إذا الصبح فات اليوم نختار المسا.
                val am = (hour % 12) * 60 + minute
                val pm = am + 12 * 60
                hour = when {
                    am > nowMinutes -> hour % 12
                    pm > nowMinutes -> hour % 12 + 12
                    else -> hour % 12
                }
            }
        }
        return (hour % 24) * 60 + minute
    }
}

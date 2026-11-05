package com.naffith.app.core

/**
 * التطبيقات المعروفة: أسماءها بالعربي والإنجليزي، حزمها، ورابط البحث داخلها (إذا التطبيق يدعمه).
 * أي تطبيق ثاني نلقاه باسمه الظاهر في الجوال (انظر AppResolver).
 */
object Apps {

    data class KnownApp(
        val id: String,
        val names: List<String>,
        val packages: List<String>,
        /** رابط البحث، و {q} مكان كلمة البحث. null = التطبيق ما يدعم البحث برابط. */
        val searchUrl: String? = null,
    )

    val known = listOf(
        KnownApp(
            "tiktok", listOf("تيك توك", "تك توك", "التيك توك", "التك توك", "تيكتوك", "tiktok", "tik tok"),
            listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill"),
            "https://www.tiktok.com/search?q={q}"
        ),
        KnownApp(
            "snapchat", listOf("سناب", "سناب شات", "السناب", "snapchat", "snap"),
            listOf("com.snapchat.android")
        ),
        KnownApp(
            "instagram", listOf("انستقرام", "انستغرام", "انستجرام", "انستا", "الانستا", "الانستقرام", "instagram", "insta"),
            listOf("com.instagram.android"),
            "https://www.instagram.com/explore/search/keyword/?q={q}"
        ),
        KnownApp(
            "x", listOf("تويتر", "التويتر", "اكس", "منصه اكس", "twitter", "x"),
            listOf("com.twitter.android"),
            "https://x.com/search?q={q}"
        ),
        KnownApp(
            "telegram", listOf("تيليجرام", "تلجرام", "تليجرام", "التلجرام", "التيليجرام", "telegram"),
            listOf("org.telegram.messenger", "org.telegram.messenger.web")
        ),
        KnownApp(
            "facebook", listOf("فيسبوك", "فيس بوك", "الفيس", "الفيسبوك", "facebook"),
            listOf("com.facebook.katana"),
            "https://www.facebook.com/search/top?q={q}"
        ),
        KnownApp(
            "spotify", listOf("سبوتيفاي", "سبوتفاي", "spotify"),
            listOf("com.spotify.music"),
            "spotify:search:{q}"
        ),
        KnownApp(
            "netflix", listOf("نتفلكس", "نتفليكس", "netflix"),
            listOf("com.netflix.mediaclient"),
            "https://www.netflix.com/search?q={q}"
        ),
        KnownApp(
            "shahid", listOf("شاهد", "shahid"),
            listOf("net.mbc.shahid")
        ),
        KnownApp(
            "playstore", listOf("المتجر", "متجر بلاي", "بلاي ستور", "قوقل بلاي", "play store"),
            listOf("com.android.vending"),
            "market://search?q={q}"
        ),
        KnownApp(
            "google", listOf("قوقل", "جوجل", "google"),
            listOf("com.google.android.googlequicksearchbox"),
            "https://www.google.com/search?q={q}"
        ),
        KnownApp(
            "chrome", listOf("كروم", "المتصفح", "chrome"),
            listOf("com.android.chrome", "com.sec.android.app.sbrowser"),
            "https://www.google.com/search?q={q}"
        ),
        KnownApp("gemini", listOf("جيميناي", "جمناي", "جميني", "جمني", "gemini"), listOf("com.google.android.apps.bard")),
        KnownApp("camera", listOf("الكاميرا", "كاميرا", "camera"), listOf("com.sec.android.app.camera", "com.google.android.GoogleCamera")),
        KnownApp("gallery", listOf("الصور", "المعرض", "الاستوديو", "gallery", "photos"), listOf("com.sec.android.gallery3d", "com.google.android.apps.photos")),
        KnownApp("settings", listOf("الاعدادات", "اعدادات الجوال", "settings"), listOf("com.android.settings")),
        KnownApp("calculator", listOf("الاله الحاسبه", "الحاسبه", "حاسبه", "calculator"), listOf("com.sec.android.app.popupcalculator", "com.google.android.calculator")),
        KnownApp("clock", listOf("الساعه", "المنبه", "منبه", "clock"), listOf("com.sec.android.app.clockpackage", "com.google.android.deskclock")),
        KnownApp("calendar", listOf("التقويم", "calendar"), listOf("com.samsung.android.calendar", "com.google.android.calendar")),
        KnownApp("messages", listOf("الرسايل", "الرسائل", "رسايل", "messages"), listOf("com.samsung.android.messaging", "com.google.android.apps.messaging")),
        KnownApp("tawakkalna", listOf("توكلنا", "tawakkalna"), listOf("sa.gov.nic.tawakkalna")),
        KnownApp("absher", listOf("ابشر", "absher"), listOf("sa.gov.moi")),
        KnownApp("hungerstation", listOf("هنقرستيشن", "هنقر", "hungerstation"), listOf("com.logistics.android")),
        KnownApp("jahez", listOf("جاهز", "jahez"), listOf("net.jahez")),
        KnownApp("careem", listOf("كريم", "careem"), listOf("com.careem.acma")),
        KnownApp("uber", listOf("اوبر", "uber"), listOf("com.ubercab")),
    )

    /** التطبيق المعروف اللي اسمه يطابق [name] (بعد التوحيد، ومع/بدون «ال»). */
    fun findKnown(name: String): KnownApp? {
        val n = ArabicText.normalize(name)
        if (n.isEmpty()) return null
        val noAl = n.removePrefix("ال")
        return known.firstOrNull { app ->
            app.names.any { alias ->
                val a = ArabicText.normalize(alias)
                a == n || a == noAl || a.removePrefix("ال") == noAl
            }
        }
    }

    /** رابط البحث داخل التطبيق، أو null إذا ما يدعمه. */
    fun searchUri(app: KnownApp, query: String): String? =
        app.searchUrl?.replace("{q}", java.net.URLEncoder.encode(query, "UTF-8").replace("+", "%20"))
}

package com.naffith.app

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.naffith.app.core.PhoneNumbers

/** يفتح التطبيقات الأخرى، مع بدائل إذا التطبيق مو مثبت. يرجع false إذا ما قدر يفتح شي. */
class AppLauncher(
    private val context: Context,
    /** ينادى لما ينفتح تطبيق ثاني بنجاح (النافذة الصغيرة تسكّر بعدها). */
    private val onLaunched: () -> Unit = {},
) {

    companion object {
        const val MAPS_PACKAGE = "com.google.android.apps.maps"
        const val YOUTUBE_PACKAGE = "com.google.android.youtube"
        val WHATSAPP_PACKAGES = listOf("com.whatsapp", "com.whatsapp.w4b")
    }

    fun openMaps(query: String): Boolean {
        val geo = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(query)))
        if (start(geo)) return true
        val web = Uri.parse("https://www.google.com/maps/search/?api=1&query=" + Uri.encode(query))
        return start(Intent(Intent.ACTION_VIEW, web))
    }

    /**
     * يبدأ الملاحة على طول في خرائط Google (بالسيارة). خرائط Google تختار أنسب نتيجة للوجهة
     * (مثلاً أقرب فرع للعثيم مول)، و«Home»/«Work» تروح للبيت/العمل المحفوظين.
     */
    fun navigate(destination: String): Boolean {
        val nav = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(destination) + "&mode=d"))
        if (isInstalled(MAPS_PACKAGE) && start(nav.setPackage(MAPS_PACKAGE))) return true
        val web = Uri.parse(
            "https://www.google.com/maps/dir/?api=1&destination=" + Uri.encode(destination) +
                "&travelmode=driving&dir_action=navigate"
        )
        return start(Intent(Intent.ACTION_VIEW, web))
    }

    fun openYouTube(query: String): Boolean {
        if (query.isBlank()) {
            context.packageManager.getLaunchIntentForPackage(YOUTUBE_PACKAGE)?.let { if (start(it)) return true }
            return start(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com")))
        }
        val search = Intent(Intent.ACTION_SEARCH)
            .setPackage(YOUTUBE_PACKAGE)
            .putExtra(SearchManager.QUERY, query)
            .putExtra("query", query)
        if (isInstalled(YOUTUBE_PACKAGE) && start(search)) return true
        val web = Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(query))
        return start(Intent(Intent.ACTION_VIEW, web))
    }

    /** يشغّل فيديو يوتيوب مباشرة. */
    fun playYouTubeVideo(videoId: String): Boolean {
        val app = Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$videoId")).setPackage(YOUTUBE_PACKAGE)
        if (isInstalled(YOUTUBE_PACKAGE) && start(app)) return true
        return start(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$videoId")))
    }

    /**
     * يفتح محادثة واتساب. الأفضل عن طريق صف واتساب في جهة الاتصال (يفتح المحادثة نفسها
     * دائماً)، وإلا برابط api.whatsapp.com بالرقم الدولي. [message] ينكتب في خانة الكتابة جاهز
     * تضغط إرسال. يرجع false إذا ما انفتح شي.
     */
    fun openWhatsAppChat(number: String, entry: ContactsRepository.WhatsAppEntry?, message: String = ""): Boolean {
        // فتح المحادثة من جهة الاتصال ما يقبل نص جاهز، فمع الرسالة نستخدم الرابط.
        if (message.isBlank() && entry != null && isInstalled(entry.packageName)) {
            val direct = Intent(Intent.ACTION_VIEW)
                .setDataAndType(entry.dataUri, entry.mimeType)
                .setPackage(entry.packageName)
            if (start(direct)) return true
        }
        val phone = PhoneNumbers.toInternational(number)
        val text = if (message.isBlank()) "" else "&text=" + Uri.encode(message)
        val uri = Uri.parse("https://api.whatsapp.com/send?phone=$phone$text")
        val pkg = WHATSAPP_PACKAGES.firstOrNull { isInstalled(it) }
        if (pkg != null && start(Intent(Intent.ACTION_VIEW, uri).setPackage(pkg))) return true
        return start(Intent(Intent.ACTION_VIEW, uri))
    }

    fun isWhatsAppInstalled(): Boolean = WHATSAPP_PACKAGES.any { isInstalled(it) }

    /** اتصال مباشر (يحتاج CALL_PHONE). */
    fun call(number: String): Boolean =
        start(Intent(Intent.ACTION_CALL, Uri.fromParts("tel", number, null)))

    /** يفتح لوحة الاتصال بالرقم جاهز، بدون إذن. */
    fun dial(number: String): Boolean =
        start(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)))

    private fun isInstalled(pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun start(intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        onLaunched()
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}

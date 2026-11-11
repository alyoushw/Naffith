package com.naffith.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * التحديثات من داخل التطبيق: يشيك على آخر إصدار في GitHub، ولو فيه أحدث يحمّله ويفتح شاشة التثبيت.
 * (يحتاج المستودع يكون عام، وإلا GitHub يرد «غير موجود».)
 */
object Updater {

    private const val REPO = "alyoushw/Naffith"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"
    private const val PREFS = "settings"
    private const val KEY_LAST_CHECK = "update_last_check"
    private const val KEY_SKIPPED = "update_skipped"
    /** كل ما تفتح مرسال نشيك (بس مو أكثر من مرة كل دقيقة، عشان حد GitHub). */
    private const val CHECK_EVERY_MS = 60 * 1000L
    private const val KEY_SKIPPED_AT = "update_skipped_at"
    /** «بعدين» تسكت التذكير ٣ ساعات بس، مو للأبد. */
    private const val SKIP_FOR_MS = 3 * 60 * 60 * 1000L
    private const val KEY_NOTIFIED = "update_notified"
    private const val CHANNEL = "updates"

    data class Release(val versionCode: Int, val name: String, val apkUrl: String, val notes: String)

    sealed interface CheckResult {
        data class Available(val release: Release) : CheckResult
        data object UpToDate : CheckResult
        data object Unreachable : CheckResult
    }

    fun currentVersionCode(context: Context): Int {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else @Suppress("DEPRECATION") info.versionCode
    }

    fun currentVersionName(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()

    /** نشيك تلقائياً بس كل كم ساعة، عشان ما نزعجك ولا نستهلك. */
    fun shouldAutoCheck(context: Context): Boolean {
        val last = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST_CHECK, 0)
        return System.currentTimeMillis() - last > CHECK_EVERY_MS
    }

    /** «بعدين» على نسخة معينة: ما نذكّرك فيها تلقائياً مرة ثانية. */
    fun skip(context: Context, versionCode: Int) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_SKIPPED, versionCode)
            .putLong(KEY_SKIPPED_AT, System.currentTimeMillis()).apply()

    fun isSkipped(context: Context, versionCode: Int): Boolean {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return p.getInt(KEY_SKIPPED, 0) >= versionCode &&
            System.currentTimeMillis() - p.getLong(KEY_SKIPPED_AT, 0) < SKIP_FOR_MS
    }

    suspend fun check(context: Context): CheckResult = withContext(Dispatchers.IO) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
        runCatching {
            val conn = (URL(API).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "Mirsal-Android")
            }
            try {
                if (conn.responseCode != 200) return@runCatching CheckResult.Unreachable
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                // الإصدارات اسمها build-<رقم البناء>، ورقم البناء هو versionCode.
                val code = json.optString("tag_name").substringAfter("build-", "").toIntOrNull()
                    ?: return@runCatching CheckResult.Unreachable
                val assets = json.optJSONArray("assets")
                var apk = ""
                if (assets != null) for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk")) apk = a.optString("browser_download_url")
                }
                if (apk.isBlank()) return@runCatching CheckResult.Unreachable
                if (code <= currentVersionCode(context)) return@runCatching CheckResult.UpToDate
                CheckResult.Available(Release(code, json.optString("name"), apk, json.optString("body")))
            } finally {
                conn.disconnect()
            }
        }.getOrDefault(CheckResult.Unreachable)
    }

    /** يحمّل ملف التحديث، و[onProgress] من 0 إلى 100. يرجع الملف أو null. */
    suspend fun download(context: Context, release: Release, onProgress: (Int) -> Unit): File? =
        withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(context.cacheDir, "updates").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val file = File(dir, "Mirsal-${release.versionCode}.apk")
                // GitHub يحوّل رابط التحميل لسيرفر ثاني؛ نلحق التحويلات بنفسنا.
                var url = URL(release.apkUrl)
                var conn: HttpURLConnection? = null
                for (hop in 0..6) {
                    val c = (url.openConnection() as HttpURLConnection).apply {
                        instanceFollowRedirects = false
                        connectTimeout = 10_000
                        readTimeout = 30_000
                        setRequestProperty("User-Agent", "Mirsal-Android")
                    }
                    val code = c.responseCode
                    if (code in 300..399) {
                        url = URL(url, c.getHeaderField("Location"))
                        c.disconnect()
                        continue
                    }
                    if (code != 200) {
                        c.disconnect()
                        return@runCatching null
                    }
                    conn = c
                    break
                }
                if (conn == null) return@runCatching null
                val total = conn.contentLengthLong
                conn.inputStream.use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var read = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            read += n
                            if (total > 0) onProgress((read * 100 / total).toInt())
                        }
                    }
                }
                conn.disconnect()
                file
            }.getOrNull()
        }

    /**
     * يشيك ولو فيه نسخة جديدة يرسل إشعار «تحديث جديد لمرسال» (مرة وحدة لكل نسخة).
     * يستخدمه الفحص في الخلفية، وفتح نافذة المساعد.
     */
    suspend fun notifyIfAvailable(context: Context) {
        val result = check(context)
        if (result !is CheckResult.Available) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_NOTIFIED, 0) >= result.release.versionCode) return
        if (showNotification(context, result.release)) {
            prefs.edit().putInt(KEY_NOTIFIED, result.release.versionCode).apply()
        }
    }

    private fun showNotification(context: Context, release: Release): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return false
        val nm = context.getSystemService(android.app.NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                android.app.NotificationChannel(CHANNEL, context.getString(R.string.upd_channel), android.app.NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val open = android.app.PendingIntent.getActivity(
            context, 7,
            Intent(context, SettingsActivity::class.java)
                .putExtra(SettingsActivity.EXTRA_START_UPDATE, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val n = androidx.core.app.NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(context.getString(R.string.upd_notif_title))
            .setContentText(context.getString(R.string.upd_notif_text, "1.0.${release.versionCode}"))
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        return runCatching { nm.notify(42, n) }.isSuccess
    }

    /** فحص في الخلفية كل ٦ ساعات (مع إنترنت). */
    fun scheduleBackgroundCheck(context: Context) {
        val request = androidx.work.PeriodicWorkRequestBuilder<UpdateWorker>(6, java.util.concurrent.TimeUnit.HOURS)
            .setConstraints(
                androidx.work.Constraints.Builder()
                    .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                    .build()
            )
            .build()
        androidx.work.WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "mirsal-update-check", androidx.work.ExistingPeriodicWorkPolicy.KEEP, request
        )
    }

    /** أندرويد يطلب إذن «تثبيت تطبيقات غير معروفة» لمرسال مرة وحدة. */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

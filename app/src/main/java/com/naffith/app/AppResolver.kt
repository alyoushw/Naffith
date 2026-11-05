package com.naffith.app

import android.content.Context
import android.content.Intent
import com.naffith.app.core.Apps
import com.naffith.app.core.ContactMatcher

/** يلقى التطبيق المثبّت في الجوال من اسمه المنطوق («التيك توك»، «البنك الأهلي»، «الكاميرا»). */
class AppResolver(private val context: Context) {

    data class InstalledApp(val label: String, val packageName: String)

    data class Resolved(val app: InstalledApp, val known: Apps.KnownApp?)

    /** كل التطبيقات اللي لها أيقونة في الجوال. */
    fun installedApps(): List<InstalledApp> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .map { InstalledApp(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
    }

    fun resolve(spokenName: String): Resolved? {
        val apps = installedApps()
        val known = Apps.findKnown(spokenName)
        // 1) تطبيق معروف مثبّت (تيك توك، سناب، انستا…).
        if (known != null) {
            known.packages.firstNotNullOfOrNull { pkg -> apps.firstOrNull { it.packageName == pkg } }
                ?.let { return Resolved(it, known) }
        }
        // 2) بالاسم الظاهر في الجوال (عربي أو إنجليزي، مع تسامح في النطق).
        val names = listOfNotNull(spokenName, known?.names?.lastOrNull())
        val best = apps.map { app -> app to names.maxOf { ContactMatcher.score(it, app.label) } }
            .maxByOrNull { it.second }
        if (best != null && best.second >= ContactMatcher.THRESHOLD) return Resolved(best.first, known)
        return null
    }

    fun launch(app: InstalledApp): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(app.packageName) ?: return false
        return runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    }

    /** يفتح البحث داخل التطبيق برابطه (إذا يدعمه). */
    fun search(resolved: Resolved, query: String): Boolean {
        val uri = resolved.known?.let { Apps.searchUri(it, query) } ?: return false
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(Intent(intent).setPackage(resolved.app.packageName)) }.isSuccess) return true
        return runCatching { context.startActivity(intent) }.isSuccess
    }
}

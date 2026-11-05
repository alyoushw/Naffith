package com.naffith.app

import android.content.Context
import android.content.Intent
import com.naffith.app.core.Apps
import com.naffith.app.core.ArabicText
import com.naffith.app.core.ContactMatcher

/**
 * يلقى التطبيق المثبّت في الجوال من اسمه المنطوق («التيك توك»، «البنك الأهلي»، «الكاميرا»).
 * وإذا ما لقاه، المستخدم يختاره من القائمة مرة وحدة ونحفظ الاسم له (اسم ثاني للتطبيق).
 */
class AppResolver(private val context: Context) {

    data class InstalledApp(val label: String, val packageName: String)

    data class Resolved(val app: InstalledApp, val known: Apps.KnownApp?)

    /** اسم ثاني محفوظ لتطبيق: [spoken] اللي يقوله المستخدم. */
    data class AppAlias(val key: String, val spoken: String, val label: String, val packageName: String)

    private val prefs = context.getSharedPreferences("app_aliases", Context.MODE_PRIVATE)

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
        // 0) اسم حفظه المستخدم قبل («تيك» ← TikTok).
        recall(spokenName)?.let { pkg ->
            apps.firstOrNull { it.packageName == pkg }?.let { return Resolved(it, known ?: knownByPackage(pkg)) }
        }
        // 1) تطبيق معروف مثبّت (تيك توك، سناب، انستا…).
        if (known != null) {
            known.packages.firstNotNullOfOrNull { pkg -> apps.firstOrNull { it.packageName == pkg } }
                ?.let { return Resolved(it, known) }
        }
        // 2) بالاسم الظاهر في الجوال (عربي أو إنجليزي، مع تسامح في النطق).
        val best = ranked(spokenName, apps).firstOrNull()
        if (best != null && best.second >= ContactMatcher.THRESHOLD) return Resolved(best.first, known)
        return null
    }

    /** التطبيقات مرتبة من الأقرب للاسم المنطوق (للقائمة لما ما نلقاه). */
    fun ranked(spokenName: String, apps: List<InstalledApp> = installedApps()): List<Pair<InstalledApp, Double>> {
        val known = Apps.findKnown(spokenName)
        val names = listOfNotNull(spokenName, known?.names?.lastOrNull())
        return apps.map { app -> app to names.maxOf { ContactMatcher.score(it, app.label) } }
            .sortedWith(compareByDescending<Pair<InstalledApp, Double>> { it.second }.thenBy { it.first.label })
    }

    // ---------- الأسماء الثانية للتطبيقات ----------

    private fun key(spoken: String): String? =
        ArabicText.normalize(spoken).removePrefix("ال").replace(" ", "").takeIf { it.isNotEmpty() }?.let { "app:$it" }

    fun remember(spokenName: String, app: InstalledApp) {
        val k = key(spokenName) ?: return
        prefs.edit().putString(k, listOf(app.packageName, spokenName.trim(), app.label).joinToString("\t")).apply()
    }

    private fun recall(spokenName: String): String? =
        key(spokenName)?.let { prefs.getString(it, null) }?.substringBefore("\t")

    fun aliases(): List<AppAlias> = prefs.all.mapNotNull { (k, v) ->
        val parts = (v as? String)?.split("\t") ?: return@mapNotNull null
        AppAlias(k, parts.getOrElse(1) { k }, parts.getOrElse(2) { parts[0] }, parts[0])
    }.sortedBy { it.spoken }

    fun forget(alias: AppAlias) = prefs.edit().remove(alias.key).apply()

    private fun knownByPackage(pkg: String) = Apps.known.firstOrNull { pkg in it.packages }

    // ---------- الفتح والبحث ----------

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

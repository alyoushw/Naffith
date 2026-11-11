package com.naffith.app

import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.launch

/** رسائل التحديث: «تحديث جديد» ← تحميل بنسبة ← شاشة التثبيت. */
object UpdateDialogs {

    /** تشييك تلقائي هادي (كل كم ساعة)؛ ما يطلع شي إلا إذا فيه تحديث جديد. */
    fun autoCheck(activity: AppCompatActivity) {
        if (offering || !Updater.shouldAutoCheck(activity)) return
        activity.lifecycleScope.launch {
            val result = Updater.check(activity)
            if (result is Updater.CheckResult.Available && !Updater.isSkipped(activity, result.release.versionCode) && !offering) {
                offer(activity, result.release, fromAuto = true)
            }
        }
    }

    /** زر «تحقق من التحديثات»: يرد عليك في كل الحالات. */
    fun manualCheck(activity: AppCompatActivity) {
        Toast.makeText(activity, R.string.upd_checking, Toast.LENGTH_SHORT).show()
        activity.lifecycleScope.launch {
            when (val result = Updater.check(activity)) {
                is Updater.CheckResult.Available -> offer(activity, result.release, fromAuto = false)
                Updater.CheckResult.UpToDate ->
                    Toast.makeText(activity, R.string.upd_latest, Toast.LENGTH_LONG).show()
                Updater.CheckResult.Unreachable ->
                    MaterialAlertDialogBuilder(activity)
                        .setTitle(R.string.upd_title)
                        .setMessage(R.string.upd_unreachable)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
            }
        }
    }

    /** رسالة التحديث ظاهرة الحين (عشان ما تطلع مرتين). */
    private var offering = false

    private fun offer(activity: AppCompatActivity, release: Updater.Release, fromAuto: Boolean) {
        if (activity.isFinishing) return
        val notes = release.notes.trim().take(600)
        val message = activity.getString(
            R.string.upd_available,
            Updater.currentVersionName(activity),
            "1.0.${release.versionCode}"
        ) + if (notes.isNotEmpty()) "\n\n$notes" else ""
        val builder = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.upd_title)
            .setMessage(message)
            .setPositiveButton(R.string.upd_now) { _, _ -> startDownload(activity, release) }
        if (fromAuto) {
            builder.setNegativeButton(R.string.upd_later) { _, _ -> Updater.skip(activity, release.versionCode) }
        } else {
            builder.setNegativeButton(R.string.cancel, null)
        }
        offering = true
        builder.setOnDismissListener { offering = false }
        builder.show()
    }

    private fun startDownload(activity: AppCompatActivity, release: Updater.Release) {
        if (!Updater.canInstall(activity)) {
            // أول مرة: أندرويد يطلب إذن تثبيت التحديثات.
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.upd_title)
                .setMessage(R.string.upd_need_permission)
                .setPositiveButton(R.string.open_settings) { _, _ -> Updater.openInstallPermission(activity) }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        val bar = LinearProgressIndicator(activity).apply { max = 100; isIndeterminate = true }
        val label = TextView(activity).apply { setText(R.string.upd_downloading) }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(label)
            addView(bar)
        }
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.upd_title)
            .setView(box)
            .setCancelable(false)
            .show()
        activity.lifecycleScope.launch {
            val file = Updater.download(activity, release) { p ->
                activity.runOnUiThread {
                    bar.isIndeterminate = false
                    bar.progress = p
                    label.text = activity.getString(R.string.upd_progress, p)
                }
            }
            dialog.dismiss()
            if (file == null) {
                Toast.makeText(activity, R.string.upd_failed, Toast.LENGTH_LONG).show()
            } else {
                runCatching { Updater.install(activity, file) }
                    .onFailure { Toast.makeText(activity, R.string.upd_failed, Toast.LENGTH_LONG).show() }
            }
        }
    }
}

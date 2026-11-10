package com.naffith.app

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/** فحص التحديثات في الخلفية: لو فيه نسخة جديدة يطلع إشعار. */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        Updater.notifyIfAvailable(applicationContext)
        return Result.success()
    }
}

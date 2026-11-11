package com.naffith.app

import android.content.ContentProviderOperation
import android.content.Context
import android.provider.CallLog
import android.provider.ContactsContract

/** آخر رقم في سجل المكالمات، وحفظ رقم في جهات الاتصال. */
object CallLogHelper {

    data class LastCall(val number: String, val name: String, val time: Long)

    fun last(context: Context): LastCall? = runCatching {
        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.DATE),
            null, null, CallLog.Calls.DATE + " DESC"
        )?.use { c ->
            while (c.moveToNext()) {
                val number = c.getString(0).orEmpty()
                if (number.isBlank()) continue // رقم مخفي
                return@runCatching LastCall(number, c.getString(1).orEmpty(), c.getLong(2))
            }
            null
        }
    }.getOrNull()

    /** يحفظ جهة اتصال في الجوال. يرجع false إذا ما قدر. */
    fun saveContact(context: Context, name: String, number: String): Boolean = runCatching {
        val ops = arrayListOf(
            ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                .build(),
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                .build(),
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, number)
                .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                .build(),
        )
        context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
        true
    }.getOrDefault(false)

    /** «قبل ٣ دقايق». */
    fun ago(time: Long): String {
        val m = ((System.currentTimeMillis() - time) / 60_000).toInt()
        return when {
            m < 1 -> "قبل شوي"
            m == 1 -> "قبل دقيقة"
            m == 2 -> "قبل دقيقتين"
            m < 60 -> "قبل $m دقيقة"
            m < 120 -> "قبل ساعة"
            m < 24 * 60 -> "قبل ${m / 60} ساعات"
            else -> "قبل ${m / 1440} يوم"
        }
    }
}

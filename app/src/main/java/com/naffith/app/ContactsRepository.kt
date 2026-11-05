package com.naffith.app

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import com.naffith.app.core.Contact
import com.naffith.app.core.PhoneNumbers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** يقرأ جهات الاتصال اللي لها أرقام. يحتاج إذن READ_CONTACTS. */
class ContactsRepository(private val context: Context) {

    /** صف واتساب داخل جهة الاتصال: فتحه يفتح المحادثة مباشرة في واتساب. */
    data class WhatsAppEntry(val dataUri: Uri, val mimeType: String, val packageName: String)

    companion object {
        private val WHATSAPP_MIME_TYPES = mapOf(
            "vnd.android.cursor.item/vnd.com.whatsapp.profile" to "com.whatsapp",
            "vnd.android.cursor.item/vnd.com.whatsapp.w4b.profile" to "com.whatsapp.w4b",
        )
    }

    suspend fun loadContacts(): List<Contact> = withContext(Dispatchers.IO) {
        val result = ArrayList<Contact>()
        val projection = arrayOf(Phone.CONTACT_ID, Phone.DISPLAY_NAME_PRIMARY, Phone.NUMBER)
        context.contentResolver.query(Phone.CONTENT_URI, projection, null, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(Phone.CONTACT_ID)
            val nameCol = cursor.getColumnIndexOrThrow(Phone.DISPLAY_NAME_PRIMARY)
            val numberCol = cursor.getColumnIndexOrThrow(Phone.NUMBER)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameCol) ?: continue
                val number = cursor.getString(numberCol) ?: continue
                result.add(Contact(cursor.getString(idCol) ?: "", name, number))
            }
        }
        result
    }

    /**
     * يدوّر على صف واتساب المرتبط بجهة الاتصال (واتساب يضيفه للجهات المسجّلة عنده).
     * إذا للجهة أكثر من رقم، نفضّل الصف اللي رقمه يطابق الرقم المختار.
     */
    suspend fun findWhatsAppEntry(contact: Contact): WhatsAppEntry? = withContext(Dispatchers.IO) {
        if (contact.id.isEmpty()) return@withContext null
        val mimeTypes = WHATSAPP_MIME_TYPES.keys.toList()
        val selection = "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} IN (?, ?)"
        val args = arrayOf(contact.id) + mimeTypes
        val projection = arrayOf(ContactsContract.Data._ID, ContactsContract.Data.MIMETYPE, ContactsContract.Data.DATA1)
        val wanted = PhoneNumbers.digitsOnly(contact.number).takeLast(9)
        var fallback: WhatsAppEntry? = null
        runCatching {
            context.contentResolver.query(ContactsContract.Data.CONTENT_URI, projection, selection, args, null)?.use { c ->
                while (c.moveToNext()) {
                    val mime = c.getString(1) ?: continue
                    val entry = WhatsAppEntry(
                        ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, c.getLong(0)),
                        mime,
                        WHATSAPP_MIME_TYPES.getValue(mime)
                    )
                    val jidDigits = PhoneNumbers.digitsOnly(c.getString(2).orEmpty())
                    if (wanted.isNotEmpty() && jidDigits.endsWith(wanted)) return@runCatching entry
                    if (fallback == null) fallback = entry
                }
            }
            fallback
        }.getOrNull()
    }
}

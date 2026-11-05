package com.naffith.app

import android.content.Context
import android.provider.ContactsContract.CommonDataKinds.Phone
import com.naffith.app.core.Contact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** يقرأ جهات الاتصال اللي لها أرقام. يحتاج إذن READ_CONTACTS. */
class ContactsRepository(private val context: Context) {

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
}

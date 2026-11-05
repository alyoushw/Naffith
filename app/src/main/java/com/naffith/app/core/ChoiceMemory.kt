package com.naffith.app.core

/**
 * يتعلم من اختيارات المستخدم: إذا اختار جهة اتصال من القائمة بعد ما قال «الجنوبي»،
 * المرة الجاية «الجنوبي» يروح لنفس الجهة مباشرة بدون قائمة.
 */
class ChoiceMemory(private val store: Store) {

    /** تخزين بسيط (SharedPreferences في التطبيق، وخريطة في الاختبارات). */
    interface Store {
        fun get(key: String): String?
        fun put(key: String, value: String)
        fun remove(key: String)
        fun clear()
    }

    fun remember(spokenName: String, contact: Contact) {
        val key = key(spokenName) ?: return
        store.put(key, fingerprint(contact))
    }

    /** يرجع جهة الاتصال المحفوظة لأي صيغة من الاسم، إذا كانت لسا موجودة في الجهاز. */
    fun recall(spokenNames: List<String>, contacts: List<Contact>): Contact? {
        for (name in spokenNames) {
            val key = key(name) ?: continue
            val saved = store.get(key) ?: continue
            val contact = contacts.firstOrNull { fingerprint(it) == saved }
            if (contact != null) return contact
            store.remove(key) // الجهة انحذفت أو تغيّر رقمها
        }
        return null
    }

    fun forgetAll() = store.clear()

    private fun key(name: String): String? =
        ArabicText.normalize(name).replace(" ", "").takeIf { it.isNotEmpty() }?.let { "choice:$it" }

    private fun fingerprint(c: Contact): String =
        ArabicText.normalize(c.name) + "|" + PhoneNumbers.digitsOnly(c.number).takeLast(9)
}

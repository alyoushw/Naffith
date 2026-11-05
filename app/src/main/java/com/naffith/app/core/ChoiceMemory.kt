package com.naffith.app.core

/**
 * الأسماء الثانية (الألقاب): «روحي» = «أم محمد»، «الجنوبي» = «الجنوبي HSN».
 * تنحفظ لما يختار المستخدم جهة اتصال من القائمة، أو يضيفها بنفسه من الإعدادات،
 * والمرة الجاية الاسم يروح لنفس الجهة مباشرة بدون قائمة.
 */
class ChoiceMemory(private val store: Store) {

    /** تخزين بسيط (SharedPreferences في التطبيق، وخريطة في الاختبارات). */
    interface Store {
        fun get(key: String): String?
        fun put(key: String, value: String)
        fun remove(key: String)
        fun clear()
        fun all(): Map<String, String>
    }

    /** اسم ثاني محفوظ: [spoken] اللي يقوله المستخدم، و[contactName]/[number] الجهة. */
    data class Alias(val key: String, val spoken: String, val contactName: String, val number: String)

    fun remember(spokenName: String, contact: Contact) {
        val key = key(spokenName) ?: return
        // البصمة أول (للمطابقة)، وبعدها النصوص الأصلية (للعرض في الإعدادات).
        store.put(key, listOf(fingerprint(contact), spokenName.trim(), contact.name, contact.number).joinToString(SEP))
    }

    /** يرجع جهة الاتصال المحفوظة لأي صيغة من الاسم، إذا كانت لسا موجودة في الجهاز. */
    fun recall(spokenNames: List<String>, contacts: List<Contact>): Contact? {
        for (name in spokenNames) {
            val key = key(name) ?: continue
            val saved = store.get(key)?.substringBefore(SEP) ?: continue
            val contact = contacts.firstOrNull { fingerprint(it) == saved }
            if (contact != null) return contact
            store.remove(key) // الجهة انحذفت أو تغيّر رقمها
        }
        return null
    }

    /** كل الأسماء الثانية المحفوظة، مرتبة أبجدياً. */
    fun all(): List<Alias> = store.all()
        .filterKeys { it.startsWith(PREFIX) }
        .map { (key, value) ->
            val parts = value.split(SEP)
            Alias(
                key = key,
                spoken = parts.getOrNull(1) ?: key.removePrefix(PREFIX),
                // صيغة قديمة: «الاسم الموحّد|آخر 9 أرقام».
                contactName = parts.getOrNull(2) ?: parts[0].substringBefore("|"),
                number = parts.getOrNull(3) ?: parts[0].substringAfter("|", ""),
            )
        }
        .sortedBy { it.spoken }

    fun forget(alias: Alias) = store.remove(alias.key)

    fun forgetAll() = store.clear()

    private fun key(name: String): String? =
        ArabicText.normalize(name).replace(" ", "").takeIf { it.isNotEmpty() }?.let { PREFIX + it }

    private fun fingerprint(c: Contact): String =
        ArabicText.normalize(c.name) + "|" + PhoneNumbers.digitsOnly(c.number).takeLast(9)

    private companion object {
        const val PREFIX = "choice:"
        const val SEP = "\t"
    }
}

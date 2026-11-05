package com.naffith.app.core

object PhoneNumbers {

    fun digitsOnly(number: String): String =
        number.map { ArabicText.normalizeChar(it) }.filter { it.isDigit() }.joinToString("")

    /**
     * يحوّل الرقم إلى الصيغة الدولية التي يحتاجها واتساب (بدون + أو 00).
     * الأرقام المحلية تُعتبر سعودية افتراضياً: 0501234567 ← 966501234567.
     */
    fun toInternational(number: String, defaultCountryCode: String = "966"): String {
        val trimmed = number.trim()
        val digits = digitsOnly(trimmed)
        return when {
            digits.isEmpty() -> ""
            trimmed.startsWith("+") -> digits
            digits.startsWith("00") -> digits.drop(2)
            digits.startsWith(defaultCountryCode) && digits.length > 10 -> digits
            digits.startsWith("0") -> defaultCountryCode + digits.drop(1)
            digits.length == 9 && digits.startsWith("5") -> defaultCountryCode + digits
            else -> digits
        }
    }
}

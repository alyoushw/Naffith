package com.naffith.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactMatcherTest {

    private var nextId = 0
    private fun c(name: String, number: String = "05000000${nextId.toString().padStart(2, '0')}") =
        Contact((nextId++).toString(), name, number)

    private fun single(result: MatchResult): Contact {
        assertTrue("expected Single but was $result", result is MatchResult.Single)
        return (result as MatchResult.Single).contact
    }

    @Test fun normalizationIgnoresHamzaTaMarbutaAndAlefMaqsura() {
        assertEquals(ArabicText.normalize("أحمد"), ArabicText.normalize("احمد"))
        assertEquals(ArabicText.normalize("إيمان"), ArabicText.normalize("ايمان"))
        assertEquals(ArabicText.normalize("فاطمة"), ArabicText.normalize("فاطمه"))
        assertEquals(ArabicText.normalize("مصطفى"), ArabicText.normalize("مصطفي"))
        assertEquals(ArabicText.normalize("مُحَمَّد"), ArabicText.normalize("محمد"))
        assertEquals(ArabicText.normalize("محـــمد"), ArabicText.normalize("محمد"))
    }

    @Test fun exactAfterNormalization() {
        assertEquals(1.0, ContactMatcher.score("احمد", "أحمد"), 0.0)
        assertEquals(1.0, ContactMatcher.score("فاطمه", "فاطمة"), 0.0)
        assertEquals(1.0, ContactMatcher.score("ليلي", "ليلى"), 0.0)
        assertEquals(1.0, ContactMatcher.score("عبد الله", "عبدالله"), 0.0)
    }

    @Test fun singleMatch() {
        val sara = c("سارة")
        val contacts = listOf(c("خالد"), sara, c("فهد"))
        assertEquals(sara, single(ContactMatcher.match(listOf("ساره"), contacts)))
    }

    @Test fun partialNameMatchesFullName() {
        val noura = c("نورة العتيبي")
        assertEquals(noura, single(ContactMatcher.match(listOf("نوره"), listOf(noura, c("فهد")))))
    }

    @Test fun smallTypoStillMatches() {
        val abdulrahman = c("عبدالرحمن")
        assertEquals(abdulrahman, single(ContactMatcher.match(listOf("عبد الرحمان"), listOf(abdulrahman, c("سعد")))))
    }

    @Test fun multipleMatchesReturnList() {
        val a1 = c("أحمد علي")
        val a2 = c("احمد سعيد")
        val result = ContactMatcher.match(listOf("أحمد"), listOf(a1, a2, c("خالد")))
        assertTrue(result is MatchResult.Multiple)
        val names = (result as MatchResult.Multiple).matches.map { it.contact }
        assertEquals(setOf(a1, a2), names.toSet())
    }

    @Test fun uniqueExactMatchWinsOverPartials() {
        val ahmed = c("أحمد")
        val result = ContactMatcher.match(listOf("احمد"), listOf(ahmed, c("أحمد علي"), c("احمد سعيد")))
        assertEquals(ahmed, single(result))
    }

    @Test fun sameNameDifferentNumbersIsMultiple() {
        val result = ContactMatcher.match(
            listOf("أبوي"),
            listOf(Contact("1", "أبوي", "0501111111"), Contact("1", "أبوي", "0552222222"))
        )
        assertTrue(result is MatchResult.Multiple)
    }

    @Test fun duplicateEntriesAreMerged() {
        val result = ContactMatcher.match(
            listOf("أمي"),
            listOf(Contact("1", "أمي", "0501111111"), Contact("2", "امي", "+966 50 111 1111"))
        )
        assertTrue(result is MatchResult.Single)
    }

    @Test fun alternativesHandleAttachedBa() {
        val mohammed = c("محمد")
        assertEquals(mohammed, single(ContactMatcher.match(listOf("بمحمد", "محمد"), listOf(mohammed, c("سلمان")))))
    }

    @Test fun nameStartingWithBaIsNotBroken() {
        val bandar = c("بندر")
        assertEquals(bandar, single(ContactMatcher.match(listOf("بندر", "ندر"), listOf(bandar, c("نادر")))))
    }

    @Test fun unrelatedNameIsNone() {
        assertEquals(MatchResult.None, ContactMatcher.match(listOf("يوسف"), listOf(c("خالد"), c("سارة"))))
    }

    @Test fun emptyQueryIsNone() {
        assertEquals(MatchResult.None, ContactMatcher.match(emptyList(), listOf(c("خالد"))))
    }

    @Test fun phoneNumbersToInternational() {
        assertEquals("966501234567", PhoneNumbers.toInternational("0501234567"))
        assertEquals("966501234567", PhoneNumbers.toInternational("+966 50 123 4567"))
        assertEquals("966501234567", PhoneNumbers.toInternational("00966501234567"))
        assertEquals("966501234567", PhoneNumbers.toInternational("501234567"))
        assertEquals("966501234567", PhoneNumbers.toInternational("٠٥٠١٢٣٤٥٦٧"))
        assertEquals("201001234567", PhoneNumbers.toInternational("+20 100 123 4567"))
    }

    // ---------- «ال» والعربي/الإنجليزي ----------

    @Test fun articleIsIgnored() {
        val janoubi = c("جنوبي")
        assertEquals(janoubi, single(ContactMatcher.match(listOf("الجنوبي"), listOf(janoubi, c("خالد")))))
    }

    @Test fun arabicPartOfMixedName() {
        val janoubi = c("الجنوبي HSN")
        assertEquals(janoubi, single(ContactMatcher.match(listOf("الجنوبي"), listOf(janoubi, c("سارة")))))
    }

    @Test fun spelledEnglishLettersMatchAcronym() {
        val janoubi = c("الجنوبي HSN")
        val other = c("الجنوبي")
        // «الجنوبي اتش اس ان» يطابق «الجنوبي HSN» تماماً، فيغلب «الجنوبي» لحاله.
        assertEquals(janoubi, single(ContactMatcher.match(listOf("الجنوبي اتش اس ان"), listOf(janoubi, other))))
        assertEquals(janoubi, single(ContactMatcher.match(listOf("اتش اس ان"), listOf(janoubi, c("فهد")))))
    }

    @Test fun arabicSpeechMatchesEnglishName() {
        val mohammed = c("Mohammed")
        assertEquals(mohammed, single(ContactMatcher.match(listOf("محمد"), listOf(mohammed, c("Sara")))))
        val khalid = c("Khalid Alotaibi")
        assertEquals(khalid, single(ContactMatcher.match(listOf("خالد"), listOf(khalid, c("Fahad")))))
        val abdullah = c("Abdullah")
        assertEquals(abdullah, single(ContactMatcher.match(listOf("عبدالله"), listOf(abdullah, c("Noura")))))
    }

    @Test fun englishSpeechMatchesArabicName() {
        val fahad = c("فهد")
        assertEquals(fahad, single(ContactMatcher.match(listOf("Fahad"), listOf(fahad, c("نورة")))))
    }

    @Test fun phoneticDoesNotMatchUnrelatedNames() {
        assertEquals(MatchResult.None, ContactMatcher.match(listOf("محمد"), listOf(c("Sara"), c("Noura"))))
    }

    @Test fun suggestionsWhenNoMatch() {
        val janoubi = c("أبو جنوب")
        val contacts = listOf(janoubi, c("سارة"))
        assertEquals(MatchResult.None, ContactMatcher.match(listOf("الجنوبي"), contacts))
        val result = ContactMatcher.suggestions(listOf("الجنوبي"), contacts)
        assertTrue(result.isNotEmpty())
        assertEquals(janoubi, result.first())
    }

    @Test fun spelledLetterVariants() {
        assertTrue("الجنوبي hsn" in Phonetics.spelledLetterVariants("الجنوبي اتش اس ان"))
        assertTrue(Phonetics.spelledLetterVariants("ام محمد").isEmpty())
    }
}

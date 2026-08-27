package com.wolfy.data.companion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CompanionModelsTest {
    private fun profile(
        name: String = "Лис",
        description: String = "",
        mbti: String? = null,
        body: String = "body.none",
        warmth: Int = 50,
    ) = CompanionProfile(
        id = "test",
        name = name,
        description = description,
        mbti = mbti,
        personality = CompanionPersonality(warmth = warmth),
        appearance = CompanionAppearance(body = body),
    )

    @Test
    fun nameLimitsFollowUnicodeCodePoints() {
        // 40 кириллических букв допустимы, 41 нет: длина считается точками,
        // а не байтами.
        val ok = profile(name = "Ж".repeat(40))
        val tooLong = profile(name = "Ж".repeat(41))
        assertTrue(validateProfile(ok).valid)
        assertFalse(validateProfile(tooLong).valid)
        assertTrue(validateProfile(profile(name = "  ")).issues.contains("name"))
    }

    @Test
    fun descriptionLimitIsEnforced() {
        assertTrue(validateProfile(profile(description = "т".repeat(1200))).valid)
        assertFalse(validateProfile(profile(description = "т".repeat(1201))).valid)
    }

    @Test
    fun emojiCountAsOneCodePointAndAreNeverSplit() {
        assertTrue(validateProfile(profile(name = "🐺".repeat(40))).valid)
        assertFalse(validateProfile(profile(name = "🐺".repeat(41))).valid)
        assertEquals("🐺".repeat(40), "🐺".repeat(41).takeCodePoints(40))
    }

    @Test
    fun mbtiAllowlistIsClosed() {
        assertTrue(validateProfile(profile(mbti = "infp")).valid)
        assertTrue(validateProfile(profile(mbti = null)).valid)
        assertFalse(validateProfile(profile(mbti = "ABCD")).valid)
    }

    @Test
    fun personalityBoundsAreChecked() {
        assertTrue(validateProfile(profile(warmth = 0)).valid)
        assertTrue(validateProfile(profile(warmth = 100)).valid)
        assertFalse(validateProfile(profile(warmth = 101)).valid)
        assertFalse(validateProfile(profile(warmth = -1)).valid)
    }

    @Test
    fun canonicalHashIsStableAgainstKeyOrder() {
        // Хеш строится по фиксированному порядку ключей, поэтому одинаковый
        // характер даёт одинаковый хеш независимо от истории правок.
        val a = profile(warmth = 72)
        val b = profile(warmth = 72).copy(
            personality = CompanionPersonality(warmth = 72, energy = 50),
        )
        assertEquals(profileHash(a), profileHash(b))
    }

    @Test
    fun clothesDoNotChangePhraseProfileHash() {
        val dressed = profile(body = "body.17")
        assertEquals(profileHash(profile()), profileHash(dressed))
    }

    @Test
    fun personalityChangeChangesHash() {
        assertNotEquals(profileHash(profile()), profileHash(profile(warmth = 20)))
        assertNotEquals(profileHash(profile()), profileHash(profile(description = "тихий голос")))
        assertNotEquals(profileHash(profile()), profileHash(profile(mbti = "INFP")))
    }

    @Test
    fun fallbackPackIsExactlyHundredAndValid() {
        for (locale in listOf("ru", "en")) {
            val pack = FallbackPhrases.pack(locale)
            assertEquals(PHRASE_COUNT, pack.phrases.size, locale)
            val issues = validatePhrasePack(pack)
            assertTrue(issues.valid, locale + ":" + issues.issues.joinToString())
            val distribution = pack.phrases.groupingBy { it.scenario }.eachCount()
            for ((scenario, count) in SCENARIO_COUNTS) {
                assertEquals(count, distribution[scenario], "$locale/$scenario")
            }
        }
    }

    @Test
    fun validatorRejectsBrokenPacks() {
        val good = FallbackPhrases.pack("ru")
        val missingOne = good.copy(phrases = good.phrases.drop(1))
        assertFalse(validatePhrasePack(missingOne).valid)

        val longDash = good.copy(
            phrases = good.phrases.take(1) + good.phrases.drop(1).mapIndexed { index, phrase ->
                if (index == 0) phrase.copy(id = "session_start.99", text = "Привет ${EM_DASH} друг")
                else phrase
            },
        )
        val issues = validatePhrasePack(longDash)
        assertFalse(issues.valid)
        assertTrue(issues.issues.any { it.startsWith("prohibited:") })

        val withUrl = good.copy(
            phrases = good.phrases.take(1) + good.phrases.drop(1).mapIndexed { index, phrase ->
                if (index == 0) phrase.copy(id = "session_start.98", text = "Смотри http://example.com")
                else phrase
            },
        )
        assertFalse(validatePhrasePack(withUrl).valid)

        val duplicateIds = good.copy(phrases = good.phrases.map { it.copy(id = "same") })
        assertTrue(validatePhrasePack(duplicateIds).issues.contains("duplicateIds"))
    }

    @Test
    fun phraseTextBoundsMatchContract() {
        val good = FallbackPhrases.pack("ru")
        for (phrase in good.phrases) {
            assertTrue(phrase.text.codePointCount(0, phrase.text.length) in MIN_PHRASE..MAX_PHRASE, phrase.id)
        }
    }
}

package com.wolfy.data.companion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CompanionReactionEngineTest {
    /** Фальшивые часы: время движется только когда тест разрешает. */
    private class FakeClock(var now: Long = 0L) {
        fun tick(minutes: Int = 1) {
            now += minutes * 60_000L
        }
    }

    private fun engine(clock: FakeClock, seed: Long = 42L): CompanionReactionEngine {
        val pack = FallbackPhrases.pack("ru")
        return CompanionReactionEngine(pack, clock::now, seed)
    }

    private fun quietContext(enabled: Boolean = true) = CompanionReactionEngine.Context(
        sessionMinutes = 0,
        overlayOpen = false,
        scrolling = false,
        reactionsEnabled = enabled,
    )

    @Test
    fun fixedSeedIsDeterministic() {
        val clockA = FakeClock()
        val clockB = FakeClock()
        val a = engine(clockA, seed = 7L)
        val b = engine(clockB, seed = 7L)
        val first = a.decide(CompanionReactionEngine.Event.SessionStart, quietContext())
        val second = b.decide(CompanionReactionEngine.Event.SessionStart, quietContext())
        assertEquals(first.phrase?.id, second.phrase?.id)
        clockA.tick(10)
        clockB.tick(10)
        assertEquals(
            a.decide(CompanionReactionEngine.Event.PageCompleted, quietContext()).phrase?.id,
            b.decide(CompanionReactionEngine.Event.PageCompleted, quietContext()).phrase?.id,
        )
    }

    @Test
    fun unpromptedReactionsRespectSevenMinuteGap() {
        val clock = FakeClock()
        val reactions = engine(clock)
        // Стартовое событие prompted, показываем. У стартовых реплик кулдаун
        // двадцать минут, поэтому тишина продлится не семь минут, а двадцать.
        assertTrue(reactions.decide(CompanionReactionEngine.Event.SessionStart, quietContext()).phrase != null)
        clock.tick(1)
        assertNull(reactions.decide(CompanionReactionEngine.Event.PageCompleted, quietContext()).phrase)
        clock.tick(7)
        assertNull(reactions.decide(CompanionReactionEngine.Event.PageCompleted, quietContext()).phrase)
        clock.tick(13)
        assertTrue(reactions.decide(CompanionReactionEngine.Event.PageCompleted, quietContext()).phrase != null)
    }

    @Test
    fun sessionCapStopsUnpromptedOnly() {
        val clock = FakeClock()
        val reactions = engine(clock)
        reactions.newSession()
        var shown = 0
        // Десять часов тиков: непрошеных должно быть не больше пяти.
        for (minute in 1..600) {
            clock.tick(1)
            val context = CompanionReactionEngine.Context(
                sessionMinutes = minute,
                overlayOpen = false,
                scrolling = false,
            )
            val decision = reactions.decide(CompanionReactionEngine.Event.SteadyReading, context)
            if (decision.phrase != null) shown += 1
        }
        assertEquals(CompanionReactionEngine.MAX_PER_SESSION, shown)
    }

    @Test
    fun overlayAndScrollSuppressEverything() {
        val clock = FakeClock()
        val reactions = engine(clock)
        val overlay = CompanionReactionEngine.Context(0, overlayOpen = true, scrolling = false)
        val scroll = CompanionReactionEngine.Context(0, overlayOpen = false, scrolling = true)
        clock.tick(100)
        assertNull(reactions.decide(CompanionReactionEngine.Event.ChapterCompleted, overlay).phrase)
        assertNull(reactions.decide(CompanionReactionEngine.Event.ChapterCompleted, scroll).phrase)
        assertTrue(reactions.decide(CompanionReactionEngine.Event.ChapterCompleted, quietContext()).phrase != null)
    }

    @Test
    fun disabledReactionsSilenceEngine() {
        val clock = FakeClock()
        val reactions = engine(clock)
        assertNull(reactions.decide(CompanionReactionEngine.Event.SessionStart, quietContext(enabled = false)).phrase)
    }

    @Test
    fun noRepeatWithinRecentWindow() {
        val clock = FakeClock()
        val reactions = engine(clock)
        val shownIds = mutableListOf<String>()
        // Много показов с кулдаунами: недавние ID не повторяются.
        for (round in 0 until 12) {
            clock.tick(10)
            val decision = reactions.decide(CompanionReactionEngine.Event.PageCompleted, quietContext())
            decision.phrase?.let {
                assertTrue(it.id !in shownIds.takeLast(20), it.id)
                shownIds.add(it.id)
            }
        }
    }

    @Test
    fun seedDependsOnProfileAndDay() {
        assertNotEquals(
            CompanionReactionEngine.seedFor("one", 1L),
            CompanionReactionEngine.seedFor("two", 1L),
        )
        assertNotEquals(
            CompanionReactionEngine.seedFor("one", 1L),
            CompanionReactionEngine.seedFor("one", 2L),
        )
        assertEquals(
            CompanionReactionEngine.seedFor("one", 1L),
            CompanionReactionEngine.seedFor("one", 1L),
        )
    }

    @Test
    fun moodEventsPickMoodScenarioOnly() {
        val clock = FakeClock()
        val reactions = engine(clock)
        clock.tick(100)
        val decision = reactions.decide(
            CompanionReactionEngine.Event.Mood(MoodScorer.JOY),
            quietContext(),
        )
        assertTrue(decision.phrase == null || decision.phrase.scenario == "mood_joy")
    }
}

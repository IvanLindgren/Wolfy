package com.wolfy.data.companion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MoodScorerTest {
    @Test
    fun goldenCorpus() {
        val cases = listOf(
            "They laughed and danced all night. What a wonderful day!" to MoodScorer.JOY,
            "She cried quietly. The grief was heavy, tears would not stop." to MoodScorer.SADNESS,
            "Suddenly he heard a scream. Danger! Run, escape now!" to MoodScorer.TENSION,
            "A secret whispered in the dark. A strange shadow vanished behind the fog." to MoodScorer.MYSTERY,
            "The morning was calm and quiet. A gentle warm breeze." to MoodScorer.CALM,
            "The train arrived at six. He bought a ticket and found his seat." to MoodScorer.NEUTRAL,
        )
        for ((text, expected) in cases) {
            assertEquals(expected, MoodScorer.analyze(text).mood, text)
        }
    }

    @Test
    fun negationFlipsLocalSentiment() {
        // «не страшно» не должно тянуть в напряжение сильнее радости рядом.
        val plain = MoodScorer.analyze("I am happy today")
        val negated = MoodScorer.analyze("I am not happy today")
        assertTrue(negated.confidence < plain.confidence || negated.mood != MoodScorer.JOY)
    }

    @Test
    fun lowConfidenceReturnsNeutral() {
        // Одна слабая подсказка не дотягивает до порога уверенности.
        val result = MoodScorer.analyze("He closed the door and sat down, maybe with a smile somewhere")
        assertEquals(MoodScorer.NEUTRAL, result.mood)
    }

    @Test
    fun longInputIsBounded() {
        val filler = "word ".repeat(5000)
        val withTail = filler + "suddenly a scream and blood and danger"
        val started = kotlin.time.TimeSource.Monotonic.markNow()
        val result = MoodScorer.analyze(withTail)
        val elapsed = started.elapsedNow()
        assertTrue(elapsed < kotlin.time.Duration.parse("100ms"), "too slow: $elapsed")
        assertEquals(MoodScorer.TENSION, result.mood)
    }

    @Test
    fun difficultyIsIndependentScore() {
        val plain = MoodScorer.analyze("The cat sat on the mat.")
        val hard = MoodScorer.analyze("Nevertheless, hitherto, consequently, furthermore, thereby.")
        assertTrue(hard.difficulty > plain.difficulty)
        assertTrue(hard.difficulty in 0f..1f)
    }
}

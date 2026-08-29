package com.wolfy.theme

import java.io.File
import kotlin.test.Test
import kotlin.test.fail

/**
 * Темп движения задаётся только из [WolfyMotion].
 *
 * Правило записано в `Motion.kt` словами: «`tween(300)` внутри виджета не знает
 * ни о какой настройке и едет всегда». Словами оно и нарушалось — половина
 * анимаций компаньона была на сырых спеках, и переключатель «уменьшить
 * движение» до них не доходил. Правилу, которое некому напомнить, нужен сторож.
 *
 * Проверяется исходник, а не поведение: поймать это на выполнении можно только
 * глазами на устройстве с включённой настройкой, а такой проверки не бывает в
 * CI.
 */
class MotionDisciplineTest {

    /**
     * Бесконечное дыхание — единственное исключение.
     *
     * У него своя долгая длительность, не связанная с темпом переходов, и оно
     * гасится целиком через `motion.still`, а не длительностью. Файлы
     * перечислены поимённо: исключение должно быть решением, а не привычкой.
     */
    private val breathing = setOf("Flame.kt", "WolfyCompanion.kt")

    private val rawSpec = Regex("""\b(tween|spring)\s*\(""")
    private val bareTransition = Regex("""\b(fadeIn|fadeOut|scaleIn|scaleOut|slideInVertically|slideOutVertically|slideInHorizontally|slideOutHorizontally)\s*\(\s*\)""")

    @Test
    fun `длительность анимаций приходит из темпа темы`() {
        val offenders = mutableListOf<String>()

        for (file in sourceFiles()) {
            if (file.name in breathing) continue
            file.readLines().forEachIndexed { index, line ->
                val code = line.substringBefore("//")
                if (code.contains("import ")) return@forEachIndexed
                if (rawSpec.containsMatchIn(code) && !code.contains("motion.")) {
                    offenders += "${file.name}:${index + 1}: ${code.trim()}"
                }
                if (bareTransition.containsMatchIn(code)) {
                    offenders += "${file.name}:${index + 1}: ${code.trim()}"
                }
            }
        }

        if (offenders.isNotEmpty()) {
            fail(
                "анимация в обход WolfyMotion — настройка «уменьшить движение» её не остановит.\n" +
                    "Задавайте длительность через motion.paced(motion.quick) или motion.settling():\n" +
                    offenders.joinToString("\n"),
            )
        }
    }

    private fun sourceFiles(): List<File> {
        // Тест запускается из каталога модуля shared.
        val roots = listOf(File("src/commonMain/kotlin"), File("src/desktopMain/kotlin"))
            .filter { it.isDirectory }
        if (roots.isEmpty()) fail("не найдены исходники: тест запущен не из каталога модуля")
        return roots.flatMap { root ->
            root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        }.filterNot { it.parentFile.name == "theme" }
    }
}

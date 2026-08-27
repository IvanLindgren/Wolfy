package com.wolfy.data.companion

/**
 * Встроенный нейтральный набор реплик.
 *
 * Он нужен как гарантия работоспособности: компаньон создаётся офлайн и без
 * вызова модели, поэтому сотня спокойных фраз лежит в приложении. Персональный
 * набор, сгенерированный сервером, заменяет его, но их отсутствие не должно
 * ломать ничего.
 *
 * Тексты не давят, не стыдят за перерыв и не претендуют на знание книги или
 * чувств читателя. Длинное тире не используется: реплики идут в интерфейс, где
 * оно запрещено правилами продукта.
 */
object FallbackPhrases {
    /** Набор для локали; незнакомая локаль получает русский. */
    fun pack(locale: String): CompanionPhrasePack {
        val phrases = if (locale == "en") EN else RU
        return CompanionPhrasePack(
            schemaVersion = 1,
            profileHash = "",
            locale = if (locale == "en") "en" else "ru",
            generatedAt = 0,
            source = CompanionPhrasePack.SOURCE_FALLBACK,
            phrases = phrases,
        )
    }

    private fun phrase(scenario: String, index: Int, text: String, minMinutes: Int = 0, cooldown: Int = 20, weight: Int = 1, moods: List<String> = emptyList(), motion: String = "none") =
        CompanionPhrase(
            id = "$scenario.${(index + 1).toString().padStart(2, '0')}",
            scenario = scenario,
            text = text,
            minMinutes = minMinutes,
            cooldownMinutes = cooldown,
            weight = weight,
            moods = moods,
            motion = motion,
        )

    private val RU: List<CompanionPhrase> = buildList {
        // session_start: 10
        repeatScenario("session_start", 10) { i ->
            when (i) {
                0 -> phrase("session_start", i, "Ну что, почитаем немного?", motion = "wave")
                1 -> phrase("session_start", i, "Рад(а) тебя видеть. С чего начнём?", motion = "wave")
                2 -> phrase("session_start", i, "Устраивайся, я побуду рядом.", motion = "wave")
                3 -> phrase("session_start", i, "Хорошее время для страницы другой.", motion = "nod")
                4 -> phrase("session_start", i, "Сегодня без спешки. Читай как удобно.")
                5 -> phrase("session_start", i, "Я здесь, если захочешь поговорить о прочитанном.")
                6 -> phrase("session_start", i, "Начнём с того места, где остановились.")
                7 -> phrase("session_start", i, "Книга ждёт. Я тоже.", motion = "nod")
                8 -> phrase("session_start", i, "Пару страниц? Это уже немало.")
                else -> phrase("session_start", i, "Тихий час для чтения. Обойдёмся без суеты.")
            }
        }
        // session_resume: 8
        repeatScenario("session_resume", 8) { i ->
            when (i) {
                0 -> phrase("session_resume", i, "С возвращением. Продолжаем?")
                1 -> phrase("session_resume", i, "Ты вернулся, и я вспомнил(а), где мы остановились.")
                2 -> phrase("session_resume", i, "Продолжим с той же страницы.")
                3 -> phrase("session_resume", i, "Отдохнул(а)? Книга никуда не делась.")
                4 -> phrase("session_resume", i, "Мы недалеко ушли. Продолжим спокойно.")
                5 -> phrase("session_resume", i, "Снова здесь. Люблю такие продолжения.")
                6 -> phrase("session_resume", i, "Возьмём с того места, где тихо стало.")
                else -> phrase("session_resume", i, "Рад(а) продолжению. Без спешки.")
            }
        }
        // steady_reading: 18
        repeatScenario("steady_reading", 18) { i ->
            when (i) {
                0 -> phrase("steady_reading", i, "Хороший темп. Мне нравится.", minMinutes = 5)
                1 -> phrase("steady_reading", i, "Ты давно не отрывался. Уважаю.", minMinutes = 10)
                2 -> phrase("steady_reading", i, "Тихо и спокойно. Так и читается лучше.", minMinutes = 5)
                3 -> phrase("steady_reading", i, "Я тут посижу, не отвлекаю.", minMinutes = 15, cooldown = 30)
                4 -> phrase("steady_reading", i, "Страницы идут одна за другой.", minMinutes = 10)
                5 -> phrase("steady_reading", i, "Приятно смотреть, как ровно идёт чтение.", minMinutes = 20, cooldown = 30)
                6 -> phrase("steady_reading", i, "Здесь у тебя хорошо получается сосредоточиться.", minMinutes = 15)
                7 -> phrase("steady_reading", i, "Если устанешь, я подожду.", minMinutes = 25, cooldown = 40)
                8 -> phrase("steady_reading", i, "Такой ритм подходит книге.", minMinutes = 10)
                9 -> phrase("steady_reading", i, "Продолжай, я рядом.", minMinutes = 20, cooldown = 30)
                10 -> phrase("steady_reading", i, "Спокойное чтение лучшее чтение.", minMinutes = 12)
                11 -> phrase("steady_reading", i, "Кажется, книга тебе нравится.", minMinutes = 18, cooldown = 35)
                12 -> phrase("steady_reading", i, "Не будем торопиться. Дольше прочитанное живёт.", minMinutes = 8)
                13 -> phrase("steady_reading", i, "Ещё немного, и будет хороший кусок.", minMinutes = 22, cooldown = 40)
                14 -> phrase("steady_reading", i, "Я примечаю, как ровно ты читаешь.", minMinutes = 30, cooldown = 45)
                15 -> phrase("steady_reading", i, "Так и держать.", minMinutes = 15)
                16 -> phrase("steady_reading", i, "Читается легко, верно?", minMinutes = 25, cooldown = 40)
                else -> phrase("steady_reading", i, "Долгое тихое чтение. Хорошее дело.", minMinutes = 35, cooldown = 50)
            }
        }
        // page_completed: 10
        repeatScenario("page_completed", 10) { i ->
            when (i) {
                0 -> phrase("page_completed", i, "Страница. Уже что-то.", cooldown = 10, motion = "peek")
                1 -> phrase("page_completed", i, "Ещё страница позади.", cooldown = 10, motion = "peek")
                2 -> phrase("page_completed", i, "Дальше, кажется, будет интереснее.", cooldown = 10, motion = "peek")
                3 -> phrase("page_completed", i, "Так и до главы недалеко.", cooldown = 12, motion = "peek")
                4 -> phrase("page_completed", i, "Перевернём спокойно.", cooldown = 10, motion = "peek")
                5 -> phrase("page_completed", i, "Каждая страница приближает конец.", cooldown = 12, motion = "peek")
                6 -> phrase("page_completed", i, "Мне тоже любопытно, что там дальше.", cooldown = 12, motion = "peek")
                7 -> phrase("page_completed", i, "Хорошая страница. Была и такая.", cooldown = 10, motion = "peek")
                8 -> phrase("page_completed", i, "Продолжение следует, как в газетах.", cooldown = 12, motion = "peek")
                else -> phrase("page_completed", i, "Отметили страницу. Идём дальше.", cooldown = 10, motion = "peek")
            }
        }
        // chapter_completed: 10
        repeatScenario("chapter_completed", 10) { i ->
            when (i) {
                0 -> phrase("chapter_completed", i, "Целая глава! Поздравляю.", cooldown = 15, motion = "nod")
                1 -> phrase("chapter_completed", i, "Глава закрыта. Хорошая точка.", cooldown = 15, motion = "nod")
                2 -> phrase("chapter_completed", i, "Ты дошёл до конца главы. Солидно.", cooldown = 15, motion = "nod")
                3 -> phrase("chapter_completed", i, "Можно выдохнуть: глава позади.", cooldown = 15, motion = "nod")
                4 -> phrase("chapter_completed", i, "Здесь удобно остановиться. Но можно и дальше.", cooldown = 15, motion = "nod")
                5 -> phrase("chapter_completed", i, "Прочитана глава. Я это запомнил(а).", cooldown = 15, motion = "nod")
                6 -> phrase("chapter_completed", i, "Такая отметина идёт книгам на пользу.", cooldown = 15, motion = "nod")
                7 -> phrase("chapter_completed", i, "Глава далась. Дальше новая история.", cooldown = 15, motion = "nod")
                8 -> phrase("chapter_completed", i, "Отметим: глава готова.", cooldown = 15, motion = "nod")
                else -> phrase("chapter_completed", i, "Конец главы всегда немного праздник.", cooldown = 15, motion = "nod")
            }
        }
        // long_session: 8
        repeatScenario("long_session", 8) { i ->
            when (i) {
                0 -> phrase("long_session", i, "Ты читаешь давно. Может, короткая пауза?", minMinutes = 45)
                1 -> phrase("long_session", i, "Час чтения это серьёзно.", minMinutes = 60)
                2 -> phrase("long_session", i, "Долгое чтение утомляет глаза. Загляни в окно.", minMinutes = 50)
                3 -> phrase("long_session", i, "Долгая сессия. Гордиться можно, но отдохни.", minMinutes = 45)
                4 -> phrase("long_session", i, "Ты прочитал(а) много. Пей воды.", minMinutes = 55)
                5 -> phrase("long_session", i, "Такой марафон заслуживает чаю.", minMinutes = 60)
                6 -> phrase("long_session", i, "Книга в хорошей форме. Читатель тоже держится.", minMinutes = 50)
                else -> phrase("long_session", i, "Мы долго вместе. Спасибо, что не гонишь.", minMinutes = 65, cooldown = 60)
            }
        }
        // return_after_break: 8
        repeatScenario("return_after_break", 8) { i ->
            when (i) {
                0 -> phrase("return_after_break", i, "С возвращением. Книга подождёт сколько нужно.")
                1 -> phrase("return_after_break", i, "Давно не виделись. Начнём отсюда.")
                2 -> phrase("return_after_break", i, "Ничего, что прошло время. Книга не обижается.")
                3 -> phrase("return_after_break", i, "Снова к чтению. По-моему, отличное решение.")
                4 -> phrase("return_after_break", i, "Мы вспомним, где остановились, вместе.")
                5 -> phrase("return_after_break", i, "Прошло времени столько, сколько прошло. Продолжим.")
                6 -> phrase("return_after_break", i, "Рад(а), что заглянул(а). Без нотаций.")
                else -> phrase("return_after_break", i, "Возвращение лучшая глава.", motion = "wave")
            }
        }
        // difficult_page: 8
        repeatScenario("difficult_page", 8) { i ->
            when (i) {
                0 -> phrase("difficult_page", i, "Здесь непросто. Это нормально.")
                1 -> phrase("difficult_page", i, "Сложная страница. Такие бывают у всех.")
                2 -> phrase("difficult_page", i, "Много новых слов. Двигайся медленно, я подожду.")
                3 -> phrase("difficult_page", i, "Тяжёлый кусок. За ним обычно легче.")
                4 -> phrase("difficult_page", i, "Не торопись здесь. Смысл догонит.")
                5 -> phrase("difficult_page", i, "Если нужно перечитать, это не поражение.")
                6 -> phrase("difficult_page", i, "Сложно значит растёшь. Я рядом.")
                else -> phrase("difficult_page", i, "Страница плотная. Переведи дух.")
            }
        }
        // mood_joy: 4
        repeatScenario("mood_joy", 4) { i ->
            when (i) {
                0 -> phrase("mood_joy", i, "Здесь стало светло. Приятно читать.", moods = listOf("joy"), cooldown = 15)
                1 -> phrase("mood_joy", i, "Весёлое место. Мне тоже понравилось.", moods = listOf("joy"), cooldown = 15)
                2 -> phrase("mood_joy", i, "Тут хорошо написано. Так и улыбнешься.", moods = listOf("joy"), cooldown = 15)
                else -> phrase("mood_joy", i, "Радость на странице передаётся.", moods = listOf("joy"), cooldown = 15)
            }
        }
        // mood_sadness: 4
        repeatScenario("mood_sadness", 4) { i ->
            when (i) {
                0 -> phrase("mood_sadness", i, "Печальная страница. Побудем в ней тихо.", moods = listOf("sadness"), cooldown = 15)
                1 -> phrase("mood_sadness", i, "Здесь грустно написано. Это тоже нужно книге.", moods = listOf("sadness"), cooldown = 15)
                2 -> phrase("mood_sadness", i, "Трогательное место. Читай медленно.", moods = listOf("sadness"), cooldown = 15)
                else -> phrase("mood_sadness", i, "Грусть в книгах честная. Я рядом.", moods = listOf("sadness"), cooldown = 15)
            }
        }
        // mood_tension: 4
        repeatScenario("mood_tension", 4) { i ->
            when (i) {
                0 -> phrase("mood_tension", i, "Как напряжено здесь.", moods = listOf("tension"), cooldown = 15)
                1 -> phrase("mood_tension", i, "Тревожная страница. Держись.", moods = listOf("tension"), cooldown = 15)
                2 -> phrase("mood_tension", i, "Стало тревожнее. Дальше узнаем.", moods = listOf("tension"), cooldown = 15)
                else -> phrase("mood_tension", i, "Сюжет набирает ход. Интересно, куда.", moods = listOf("tension"), cooldown = 15)
            }
        }
        // mood_mystery: 4
        repeatScenario("mood_mystery", 4) { i ->
            when (i) {
                0 -> phrase("mood_mystery", i, "Загадочно. Мне нравится гадать.", moods = listOf("mystery"), cooldown = 15)
                1 -> phrase("mood_mystery", i, "Что-то тут нечисто. В хорошем смысле.", moods = listOf("mystery"), cooldown = 15)
                2 -> phrase("mood_mystery", i, "Тайна сгущается. Запомним детали.", moods = listOf("mystery"), cooldown = 15)
                else -> phrase("mood_mystery", i, "Много вопросов. Значит, книга живая.", moods = listOf("mystery"), cooldown = 15)
            }
        }
        // session_end: 4
        repeatScenario("session_end", 4) { i ->
            when (i) {
                0 -> phrase("session_end", i, "Хорошо почитали. До следующего раза.")
                1 -> phrase("session_end", i, "Заканчиваем на хорошей ноте.")
                2 -> phrase("session_end", i, "Я побуду здесь, пока ты не вернёшься.")
                else -> phrase("session_end", i, "Спасибо за чтение. Отдохни.")
            }
        }
    }

    private val EN: List<CompanionPhrase> = buildList {
        repeatScenario("session_start", 10) { i ->
            when (i) {
                0 -> phrase("session_start", i, "Ready for a few pages?", motion = "wave")
                1 -> phrase("session_start", i, "Good to see you. Where do we start?", motion = "wave")
                2 -> phrase("session_start", i, "Get comfortable, I will be right here.", motion = "wave")
                3 -> phrase("session_start", i, "A good time for another page.", motion = "nod")
                4 -> phrase("session_start", i, "No rush today. Read at your pace.")
                5 -> phrase("session_start", i, "I am here if you want to talk about it.")
                6 -> phrase("session_start", i, "We pick up right where you stopped.")
                7 -> phrase("session_start", i, "The book is waiting. So am I.", motion = "nod")
                8 -> phrase("session_start", i, "A couple of pages already counts.")
                else -> phrase("session_start", i, "A quiet reading hour. No fuss.")
            }
        }
        repeatScenario("session_resume", 8) { i ->
            when (i) {
                0 -> phrase("session_resume", i, "Welcome back. Continue?")
                1 -> phrase("session_resume", i, "You are back, and I remember the place.")
                2 -> phrase("session_resume", i, "Same page as before. Shall we?")
                3 -> phrase("session_resume", i, "Rested? The book kept still.")
                4 -> phrase("session_resume", i, "We did not get far. Take it slow.")
                5 -> phrase("session_resume", i, "Here again. I like sequels like this.")
                6 -> phrase("session_resume", i, "Pick up where it got quiet.")
                else -> phrase("session_resume", i, "Glad to continue. No hurry.")
            }
        }
        repeatScenario("steady_reading", 18) { i ->
            when (i) {
                0 -> phrase("steady_reading", i, "Good pace. I like it.", minMinutes = 5)
                1 -> phrase("steady_reading", i, "You have been at it a while. Respect.", minMinutes = 10)
                2 -> phrase("steady_reading", i, "Quiet and steady. That is how books get read.", minMinutes = 5)
                3 -> phrase("steady_reading", i, "I will just sit here. Not distracting.", minMinutes = 15, cooldown = 30)
                4 -> phrase("steady_reading", i, "Pages keep turning.", minMinutes = 10)
                5 -> phrase("steady_reading", i, "Nice to watch such an even rhythm.", minMinutes = 20, cooldown = 30)
                6 -> phrase("steady_reading", i, "You focus well here.", minMinutes = 15)
                7 -> phrase("steady_reading", i, "If you get tired, I will wait.", minMinutes = 25, cooldown = 40)
                8 -> phrase("steady_reading", i, "This rhythm suits the book.", minMinutes = 10)
                9 -> phrase("steady_reading", i, "Keep going, I am around.", minMinutes = 20, cooldown = 30)
                10 -> phrase("steady_reading", i, "Calm reading is the best reading.", minMinutes = 12)
                11 -> phrase("steady_reading", i, "Seems the book got to you.", minMinutes = 18, cooldown = 35)
                12 -> phrase("steady_reading", i, "No need to rush. Lived-in books last.", minMinutes = 8)
                13 -> phrase("steady_reading", i, "A bit more and it is a solid chunk.", minMinutes = 22, cooldown = 40)
                14 -> phrase("steady_reading", i, "I notice how steadily you read.", minMinutes = 30, cooldown = 45)
                15 -> phrase("steady_reading", i, "Keep it like this.", minMinutes = 15)
                16 -> phrase("steady_reading", i, "Reads easily, right?", minMinutes = 25, cooldown = 40)
                else -> phrase("steady_reading", i, "A long quiet read. A good thing.", minMinutes = 35, cooldown = 50)
            }
        }
        repeatScenario("page_completed", 10) { i ->
            when (i) {
                0 -> phrase("page_completed", i, "One page. Already something.", cooldown = 10, motion = "peek")
                1 -> phrase("page_completed", i, "Another page behind you.", cooldown = 10, motion = "peek")
                2 -> phrase("page_completed", i, "Next one looks promising.", cooldown = 10, motion = "peek")
                3 -> phrase("page_completed", i, "A chapter is not far now.", cooldown = 12, motion = "peek")
                4 -> phrase("page_completed", i, "Turn it calmly.", cooldown = 10, motion = "peek")
                5 -> phrase("page_completed", i, "Every page brings the end closer.", cooldown = 12, motion = "peek")
                6 -> phrase("page_completed", i, "I am curious what comes next too.", cooldown = 12, motion = "peek")
                7 -> phrase("page_completed", i, "A good page. They all count.", cooldown = 10, motion = "peek")
                8 -> phrase("page_completed", i, "To be continued, like in newspapers.", cooldown = 12, motion = "peek")
                else -> phrase("page_completed", i, "Page marked. On we go.", cooldown = 10, motion = "peek")
            }
        }
        repeatScenario("chapter_completed", 10) { i ->
            when (i) {
                0 -> phrase("chapter_completed", i, "A whole chapter! Well done.", cooldown = 15, motion = "nod")
                1 -> phrase("chapter_completed", i, "Chapter closed. A good stopping point.", cooldown = 15, motion = "nod")
                2 -> phrase("chapter_completed", i, "You finished the chapter. Solid.", cooldown = 15, motion = "nod")
                3 -> phrase("chapter_completed", i, "You can exhale: the chapter is done.", cooldown = 15, motion = "nod")
                4 -> phrase("chapter_completed", i, "A neat place to stop. Or not.", cooldown = 15, motion = "nod")
                5 -> phrase("chapter_completed", i, "A chapter read. I keep count.", cooldown = 15, motion = "nod")
                6 -> phrase("chapter_completed", i, "Books benefit from milestones like this.", cooldown = 15, motion = "nod")
                7 -> phrase("chapter_completed", i, "The chapter is yours. A new story next.", cooldown = 15, motion = "nod")
                8 -> phrase("chapter_completed", i, "Let the record show: chapter done.", cooldown = 15, motion = "nod")
                else -> phrase("chapter_completed", i, "The end of a chapter is a small holiday.", cooldown = 15, motion = "nod")
            }
        }
        repeatScenario("long_session", 8) { i ->
            when (i) {
                0 -> phrase("long_session", i, "You have read for a while. A short break?", minMinutes = 45)
                1 -> phrase("long_session", i, "An hour of reading is serious business.", minMinutes = 60)
                2 -> phrase("long_session", i, "Your eyes must be tired. I will watch the book.", minMinutes = 50)
                3 -> phrase("long_session", i, "A long session. Be proud, then rest.", minMinutes = 45)
                4 -> phrase("long_session", i, "You read a lot. Drink some water.", minMinutes = 55)
                5 -> phrase("long_session", i, "Such a marathon deserves tea.", minMinutes = 60)
                6 -> phrase("long_session", i, "The book is in good shape. So is the reader.", minMinutes = 50)
                else -> phrase("long_session", i, "We have been together long. Thanks for having me.", minMinutes = 65, cooldown = 60)
            }
        }
        repeatScenario("return_after_break", 8) { i ->
            when (i) {
                0 -> phrase("return_after_break", i, "Welcome back. The book waits as long as needed.")
                1 -> phrase("return_after_break", i, "Long time. We start from here.")
                2 -> phrase("return_after_break", i, "No matter the pause. Books hold no grudge.")
                3 -> phrase("return_after_break", i, "Back to reading. A fine decision.")
                4 -> phrase("return_after_break", i, "We will recall where you stopped, together.")
                5 -> phrase("return_after_break", i, "As much time passed as passed. Onwards.")
                6 -> phrase("return_after_break", i, "Glad you dropped in. No lectures.")
                else -> phrase("return_after_break", i, "Returning is the best chapter.", motion = "wave")
            }
        }
        repeatScenario("difficult_page", 8) { i ->
            when (i) {
                0 -> phrase("difficult_page", i, "This one is hard. That is normal.")
                1 -> phrase("difficult_page", i, "A tricky page. Happens to everyone.")
                2 -> phrase("difficult_page", i, "Lots of new words. Go slowly, I will wait.")
                3 -> phrase("difficult_page", i, "A dense stretch. Usually lighter after.")
                4 -> phrase("difficult_page", i, "Do not rush here. Meaning catches up.")
                5 -> phrase("difficult_page", i, "Rereading is not defeat.")
                6 -> phrase("difficult_page", i, "Hard means growing. I am nearby.")
                else -> phrase("difficult_page", i, "A thick page. Take a breath.")
            }
        }
        repeatScenario("mood_joy", 4) { i ->
            when (i) {
                0 -> phrase("mood_joy", i, "It got brighter here. Nice to read.", moods = listOf("joy"), cooldown = 15)
                1 -> phrase("mood_joy", i, "A cheerful spot. I enjoyed it too.", moods = listOf("joy"), cooldown = 15)
                2 -> phrase("mood_joy", i, "Well written. You almost smile.", moods = listOf("joy"), cooldown = 15)
                else -> phrase("mood_joy", i, "Joy on the page is contagious.", moods = listOf("joy"), cooldown = 15)
            }
        }
        repeatScenario("mood_sadness", 4) { i ->
            when (i) {
                0 -> phrase("mood_sadness", i, "A sad page. Let us sit with it quietly.", moods = listOf("sadness"), cooldown = 15)
                1 -> phrase("mood_sadness", i, "Written with sorrow. Books need that too.", moods = listOf("sadness"), cooldown = 15)
                2 -> phrase("mood_sadness", i, "A touching place. Read slowly.", moods = listOf("sadness"), cooldown = 15)
                else -> phrase("mood_sadness", i, "Bookish sadness is honest. I am here.", moods = listOf("sadness"), cooldown = 15)
            }
        }
        repeatScenario("mood_tension", 4) { i ->
            when (i) {
                0 -> phrase("mood_tension", i, "How tense it is here.", moods = listOf("tension"), cooldown = 15)
                1 -> phrase("mood_tension", i, "An anxious page. Hold on.", moods = listOf("tension"), cooldown = 15)
                2 -> phrase("mood_tension", i, "It grew more anxious. We will find out.", moods = listOf("tension"), cooldown = 15)
                else -> phrase("mood_tension", i, "The story picks up speed. Where to, I wonder.", moods = listOf("tension"), cooldown = 15)
            }
        }
        repeatScenario("mood_mystery", 4) { i ->
            when (i) {
                0 -> phrase("mood_mystery", i, "Mysterious. I like guessing.", moods = listOf("mystery"), cooldown = 15)
                1 -> phrase("mood_mystery", i, "Something is off here. In a good way.", moods = listOf("mystery"), cooldown = 15)
                2 -> phrase("mood_mystery", i, "The mystery thickens. Remember the details.", moods = listOf("mystery"), cooldown = 15)
                else -> phrase("mood_mystery", i, "Many questions. Means the book is alive.", moods = listOf("mystery"), cooldown = 15)
            }
        }
        repeatScenario("session_end", 4) { i ->
            when (i) {
                0 -> phrase("session_end", i, "A good reading session. Until next time.")
                1 -> phrase("session_end", i, "We stop on a good note.")
                2 -> phrase("session_end", i, "I will stay here until you return.")
                else -> phrase("session_end", i, "Thanks for reading. Rest well.")
            }
        }
    }

    private inline fun MutableList<CompanionPhrase>.repeatScenario(scenario: String, count: Int, block: (Int) -> CompanionPhrase) {
        for (i in 0 until count) add(block(i))
    }
}

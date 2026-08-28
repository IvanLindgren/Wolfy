package com.wolfy.platform

/** Негромкие сигналы компаньона. Они не используют канал радио или речи. */
enum class CompanionSound { Reveal, Reaction, Ready }

/** Воспроизводит короткий локальный сигнал, не блокируя интерфейс. */
expect fun playCompanionSound(sound: CompanionSound)

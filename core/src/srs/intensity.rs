//! Интенсивность повторений.

use serde::{Deserialize, Serialize};

/// Интенсивность повторений.
///
/// Один и тот же материал можно проходить за месяц и за неделю — разница в
/// том, сколько времени читатель готов тратить каждый день. Выбор его, а не
/// приложения: «экстрим» перед экзаменом и «лёгкий» на каникулах — это один
/// человек в разные недели, а не два разных пользователя.
///
/// Интенсивность растягивает и сжимает **сроки**, но не то, сколько верных
/// ответов нужно, чтобы слово считалось выученным. Иначе «лёгкий» означал бы
/// «выучил хуже», и сравнить свои двести слов с чужими двумястами стало бы
/// невозможно.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize, Deserialize)]
pub enum Intensity {
    Gentle,
    /// По умолчанию: подходит всем, и с неё начинают, ничего не выбирая.
    #[default]
    Normal,
    Strong,
    Extreme,
}

impl Intensity {
    /// Множитель сроков. Меньше — чаще.
    ///
    /// Числа подобраны так, чтобы разница чувствовалась, но крайние режимы
    /// оставались рабочими: между «лёгким» и «экстримом» примерно впятеро.
    pub fn stretch(self) -> f32 {
        match self {
            Intensity::Gentle => 1.7,
            Intensity::Normal => 1.0,
            Intensity::Strong => 0.6,
            Intensity::Extreme => 0.35,
        }
    }

    /// Сколько слов должно забыться, чтобы Wolfy напомнил.
    ///
    /// Порог, а не срок: одно подзабытое слово не повод трогать человека, а
    /// десяток — уже повод. См. [`super::scheduler::reminder_at`].
    pub fn forgotten(self) -> usize {
        match self {
            Intensity::Gentle => 12,
            Intensity::Normal => 8,
            Intensity::Strong => 5,
            Intensity::Extreme => 3,
        }
    }

    /// По имени. Незнакомое — средняя: она подходит всем.
    ///
    /// Имена совпадают с теми, что писал клиент на Kotlin: настройки уже
    /// лежат на устройствах, и переименование стоило бы миграции ради ничего.
    pub fn of(name: &str) -> Intensity {
        match name {
            "Gentle" => Intensity::Gentle,
            "Strong" => Intensity::Strong,
            "Extreme" => Intensity::Extreme,
            _ => Intensity::Normal,
        }
    }

    /// Обратно в имя — тем же написанием, каким читается.
    pub fn name(self) -> &'static str {
        match self {
            Intensity::Gentle => "Gentle",
            Intensity::Normal => "Normal",
            Intensity::Strong => "Strong",
            Intensity::Extreme => "Extreme",
        }
    }
}

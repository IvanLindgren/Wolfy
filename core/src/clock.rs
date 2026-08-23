//! Местный календарь по сдвигу от UTC.
//!
//! Ядро не носит с собой базу часовых поясов и не спрашивает время у системы:
//! оно обязано считать одинаково на телефоне и на настольной машине и
//! проигрываться тестом без участия устройства. Поэтому «сейчас» и сдвиг
//! приходят числами, а здесь лежит только арифметика.
//!
//! Сдвиг — в минутах, а не в часах: Индия живёт на +5:30, Непал на +5:45, и
//! часами их не выразить.

/// Миллисекунды в минуте.
pub const MINUTE_MS: i64 = 60_000;

/// Миллисекунды в часе.
pub const HOUR_MS: i64 = 3_600_000;

/// Миллисекунды в сутках.
pub const DAY_MS: i64 = 24 * HOUR_MS;

/// Местный день как число.
///
/// Серия дней считается по календарю читателя, а не по эпохе: «вчера
/// позанимался, сегодня позанимался» — это про полночь там, где он живёт.
pub fn local_day(at: i64, offset_minutes: i32) -> i64 {
    (at + offset_minutes as i64 * MINUTE_MS).div_euclid(DAY_MS)
}

/// Час местного времени, 0..23.
///
/// Деление здесь по Евклиду намеренно. До эпохи и к западу от Гринвича
/// местное время выражается отрицательным числом, а обычное деление в Rust
/// округляет к нулю — час до полуночи превратился бы в отрицательный.
pub fn local_hour(at: i64, offset_minutes: i32) -> i32 {
    (at + offset_minutes as i64 * MINUTE_MS)
        .div_euclid(HOUR_MS)
        .rem_euclid(24) as i32
}

/// Ближайший наступающий местный час, не раньше `from`.
pub fn at_local_hour(from: i64, hour: i32, offset_minutes: i32) -> i64 {
    let offset = offset_minutes as i64 * MINUTE_MS;
    let local = from + offset;
    let midnight = local.div_euclid(DAY_MS) * DAY_MS;
    let mut target = midnight + hour as i64 * HOUR_MS;
    if target < local {
        target += DAY_MS;
    }
    target - offset
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Москва: +3, Нью-Йорк: −5, Катманду: +5:45.
    const MSK: i32 = 180;
    const NYC: i32 = -300;
    const KTM: i32 = 345;

    #[test]
    fn час_считается_и_к_западу_от_гринвича() {
        // 1 января 1970, 02:00 UTC.
        let at = 2 * HOUR_MS;
        assert_eq!(local_hour(at, 0), 2);
        assert_eq!(local_hour(at, MSK), 5);
        // В Нью-Йорке это ещё вчерашний вечер — то есть отрицательное число
        // до вычета остатка.
        assert_eq!(local_hour(at, NYC), 21);
    }

    #[test]
    fn день_меняется_по_местной_полуночи() {
        let at = 2 * HOUR_MS;
        assert_eq!(local_day(at, 0), 0);
        // Для Нью-Йорка сутки ещё не наступили.
        assert_eq!(local_day(at, NYC), -1);
        // А два дня подряд остаются соседними числами в любом поясе.
        assert_eq!(local_day(at + DAY_MS, NYC) - local_day(at, NYC), 1);
    }

    #[test]
    fn получасовые_пояса_не_ломаются() {
        // 00:00 UTC — это 05:45 в Катманду.
        assert_eq!(local_hour(0, KTM), 5);
        assert_eq!(local_day(0, KTM), 0);
        // А в 18:30 UTC там уже следующий день.
        assert_eq!(local_day(18 * HOUR_MS + 30 * MINUTE_MS, KTM), 1);
    }

    #[test]
    fn назначенный_час_не_уходит_в_прошлое() {
        // 04:00 в Нью-Йорке — это 09:00 UTC.
        let ночь = 9 * HOUR_MS;
        assert_eq!(local_hour(ночь, NYC), 4);

        let утро = at_local_hour(ночь, 9, NYC);
        assert_eq!(local_hour(утро, NYC), 9);
        assert!(утро > ночь);

        // А если нужный час уже прошёл, берётся завтрашний.
        let вечер = 2 * HOUR_MS;
        let завтра = at_local_hour(вечер, 9, NYC);
        assert_eq!(local_hour(завтра, NYC), 9);
        assert!(завтра > вечер);
    }
}

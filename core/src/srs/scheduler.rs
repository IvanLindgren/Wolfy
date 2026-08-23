//! Расписание повторений.

use super::card::Card;
use super::intensity::Intensity;

/// Прочность новой карточки.
pub const FULL_HP: i32 = 100;

/// Целевая вероятность вспомнить в назначенный срок.
///
/// Девяносто процентов — обычная цель интервального повторения: ниже
/// слишком много мучений, выше слишком много лишних показов.
pub const TARGET_RECALL: f32 = 0.9;

const MINUTE: i64 = 60_000;
const DAY: i64 = 1440;
const HOUR_MS: i64 = 3_600_000;
const DAY_MS: i64 = 24 * HOUR_MS;

/// Сроки в минутах.
///
/// Первая ступень — сутки: повторить назавтра то, что впервые встретил
/// сегодня. Последняя — полгода; дальше растягивать нечего, слово к этому
/// моменту либо в языке, либо не нужно.
const LADDER: [i64; 7] = [DAY, 3 * DAY, 7 * DAY, 16 * DAY, 35 * DAY, 75 * DAY, 160 * DAY];

/// Через сколько вернуть карточку, на которой ошиблись.
const RETRY_MINUTES: i64 = 10;

/// Сколько прочности возвращает ошибка.
const MISS: i32 = 15;

/// Во сколько раз период полузабывания длиннее назначенного срока.
///
/// Если к сроку помнится девять из десяти, то `2^(-k) = 0,9`, откуда
/// `k ≈ 0,152`.
const DECAY: f32 = 0.152;

/// Сколько ответов нужно, прежде чем подстраивать сроки под читателя.
const MIN_SAMPLE: i32 = 30;

/// Часы, в которые уместно напоминать.
const WAKE: i32 = 9;
const SLEEP: i32 = 22;

/// Учитывает ответ.
///
/// Чистая функция: карточка на входе, карточка на выходе. Так расписание
/// можно проверить тестом на любой истории ответов, не заводя ни библиотеки,
/// ни времени, ни устройства, — а расписание, которое нельзя проверить,
/// обязательно окажется неверным.
///
/// # Очки здоровья
///
/// У карточки есть запас прочности: сто очков в начале, ноль — когда слово
/// выучено. Верный ответ снимает очки, ошибка возвращает. Метафора не
/// украшение: она объясняет, зачем повторять слово, которое «и так знаешь» —
/// потому что у него ещё осталась прочность.
///
/// Четырёх верных ответов подряд хватает, чтобы свести прочность к нулю.
/// Каждый следующий снимает больше предыдущего: первый — двадцать,
/// четвёртый — тридцать пять. Так награда за серию видна, а одна случайная
/// удача слово не «выучивает».
///
/// # Сроки
///
/// Лесенка: сутки, трое, неделя, две с половиной, месяц, два с половиной,
/// полгода. Ошибка сбрасывает на начало и возвращает карточку через десять
/// минут — в ту же тренировку, потому что слово, которое только что не
/// вспомнилось, повторять через сутки бессмысленно.
///
/// * `right` — вспомнил ли читатель. Промежуточных оценок нет намеренно:
///   упражнения здесь объективные — собрать слово из букв можно верно или
///   неверно, — и просить читателя ещё и оценить себя значило бы спрашивать
///   то, чего он не знает.
/// * `ease` — поправка на то, как читатель отвечает на самом деле, из [`ease`].
pub fn review(card: &Card, right: bool, intensity: Intensity, ease: f32, now: i64) -> Card {
    let mut next = card.clone();
    next.reviewed_at = now;
    next.dirty = true;

    if !right {
        next.hp = (card.hp + MISS).min(FULL_HP);
        next.streak = 0;
        next.interval_days = 0;
        next.due_at = now + RETRY_MINUTES * MINUTE;
        return next;
    }

    let streak = card.streak + 1;
    let rung = ((streak - 1).max(0) as usize).min(LADDER.len() - 1);
    let minutes = RETRY_MINUTES.max((LADDER[rung] as f32 * intensity.stretch() * ease) as i64);

    next.hp = (card.hp - damage(card.streak)).max(0);
    next.streak = streak;
    next.interval_days = (minutes / DAY) as i32;
    next.due_at = now + minutes * MINUTE;
    next
}

/// Сколько прочности снимает верный ответ при такой серии.
fn damage(streak: i32) -> i32 {
    20 + 5 * streak
}

/// Вероятность вспомнить слово прямо сейчас — от нуля до единицы.
///
/// Кривая забывания в простейшем виде: помнится вдвое хуже за каждый период
/// полузабывания. Период считается из назначенного срока самой карточки, а
/// он у каждой свой, — отсюда и «индивидуальный график».
///
/// Ноль для карточки, которую ещё не повторяли: она не забыта, её просто
/// никогда и не знали.
pub fn retention(card: &Card, at: i64) -> f32 {
    let span = card.due_at - card.reviewed_at;
    if card.reviewed_at <= 0 || span <= 0 {
        return 0.0;
    }

    let elapsed = at - card.reviewed_at;
    if elapsed <= 0 {
        return 1.0;
    }

    let half_life = span as f32 / DECAY;
    2f32.powf(-(elapsed as f32) / half_life).clamp(0.0, 1.0)
}

/// Когда карточка забудется наполовину.
pub fn half_forgotten_at(card: &Card) -> Option<i64> {
    let span = card.due_at - card.reviewed_at;
    if card.reviewed_at <= 0 || span <= 0 {
        return None;
    }
    Some(card.reviewed_at + (span as f32 / DECAY) as i64)
}

/// Карточки, которым пора.
pub fn due(cards: &[Card], at: i64) -> Vec<Card> {
    let mut ready: Vec<Card> = cards
        .iter()
        .filter(|card| !card.deleted && card.due_at <= at)
        .cloned()
        .collect();
    ready.sort_by_key(|card| card.due_at);
    ready
}

/// Выученные: прочность сведена к нулю.
pub fn learned(cards: &[Card]) -> Vec<Card> {
    cards.iter().filter(|card| card.learned()).cloned().collect()
}

/// Когда напомнить о повторении.
///
/// Два условия, и срабатывает то, что наступит раньше.
///
/// Первое — накопилось: созрела [`Intensity::forgotten`]-я карточка. Ради
/// одного слова человека не трогают, ради десятка — уже стоит.
///
/// Второе — что-то забывается всерьёз: одна из карточек дошла до половины
/// своей кривой. Слово, лежащее просроченным месяц, заслуживает напоминания
/// даже в одиночку, иначе редкая колода не напомнит о себе никогда.
///
/// Оба условия читаются с той же кривой, по которой назначены сроки, —
/// поэтому напоминание и приходит тогда, когда читатель на самом деле
/// начинает забывать, а не через равные сутки.
///
/// `None` — напоминать не о чем.
///
/// `offset_minutes` — сдвиг местного времени от UTC. Ядро не носит с собой
/// базу часовых поясов и не спрашивает систему: оно обязано оставаться
/// детерминированным и одинаковым на всех платформах, а пояс знает клиент.
pub fn reminder_at(
    cards: &[Card],
    intensity: Intensity,
    now: i64,
    offset_minutes: i32,
) -> Option<i64> {
    let active: Vec<&Card> = cards
        .iter()
        .filter(|card| !card.deleted && card.due_at > 0)
        .collect();
    if active.is_empty() {
        return None;
    }

    // Порог не может быть больше самой колоды: с тремя карточками ждать
    // восьмой означало бы не напомнить никогда.
    let half = active.len().div_ceil(2).max(1);
    let target = intensity.forgotten().min(half);

    let mut ripe: Vec<i64> = active.iter().map(|card| card.due_at).collect();
    ripe.sort_unstable();
    let batch = ripe.get(target - 1).copied();
    let urgent = active.iter().filter_map(|card| half_forgotten_at(card)).min();

    let at = match (batch, urgent) {
        (Some(batch), Some(urgent)) => batch.min(urgent),
        (Some(batch), None) => batch,
        (None, Some(urgent)) => urgent,
        (None, None) => return None,
    };
    Some(waking(at.max(now), offset_minutes))
}

/// Сдвигает момент в приличное время.
///
/// Напоминание в четыре утра — не забота, а раздражение, и выключают после
/// него все уведомления сразу.
pub fn waking(at: i64, offset_minutes: i32) -> i64 {
    let hour = local_hour(at, offset_minutes);
    if (WAKE..SLEEP).contains(&hour) {
        at
    } else {
        at_local_hour(at, WAKE, offset_minutes)
    }
}

/// Час местного времени, 0..23.
pub fn local_hour(at: i64, offset_minutes: i32) -> i32 {
    let local = at + offset_minutes as i64 * MINUTE;
    local.div_euclid(HOUR_MS).rem_euclid(24) as i32
}

/// Ближайший наступающий местный час, не раньше `from`.
pub fn at_local_hour(from: i64, hour: i32, offset_minutes: i32) -> i64 {
    let offset = offset_minutes as i64 * MINUTE;
    let local = from + offset;
    let midnight = local.div_euclid(DAY_MS) * DAY_MS;
    let mut target = midnight + hour as i64 * HOUR_MS;
    if target < local {
        target += DAY_MS;
    }
    target - offset
}

/// Поправка на то, как читатель отвечает на самом деле.
///
/// Расписание рассчитано на девять верных ответов из десяти. Тот, кто
/// отвечает лучше, повторяет лишнее; тот, кто хуже, — не успевает
/// закрепить. Поправка растягивает или сжимает всю лесенку под него.
///
/// До тридцати ответов поправки нет: по десятку ответов «точность» — это
/// шум, и подстраиваться под него значит гонять читателя туда-сюда.
pub fn ease(answers: i32, right: i32) -> f32 {
    if answers < MIN_SAMPLE {
        return 1.0;
    }
    let accuracy = right as f32 / answers as f32;
    (1.0 + (accuracy - TARGET_RECALL) * 3.0).clamp(0.6, 1.8)
}

/// Через сколько дней покажется карточка — для подписи в интерфейсе.
pub fn days_ahead(card: &Card, from: i64) -> i32 {
    (((card.due_at - from) + DAY_MS - 1).div_euclid(DAY_MS)).max(0) as i32
}

#[cfg(test)]
mod tests {
    use super::*;

    const START: i64 = 1_700_000_000_000;
    const MINUTE_MS: i64 = 60_000;
    const DAY_LEN: i64 = 24 * 60 * MINUTE_MS;
    /// Москва: ядро часовых поясов не знает, пояс приходит числом.
    const MSK: i32 = 180;

    fn карточка(id: &str, hp: i32) -> Card {
        let mut card = Card::new(id, "book", "book");
        card.hp = hp;
        card.due_at = START;
        card.added_at = START;
        card
    }

    fn обычная() -> Card {
        карточка("1", FULL_HP)
    }

    #[test]
    fn четыре_верных_ответа_выучивают_слово() {
        let mut card = обычная();
        let mut moment = START;
        for _ in 0..4 {
            card = review(&card, true, Intensity::Normal, 1.0, moment);
            moment = card.due_at;
        }
        assert_eq!(card.hp, 0, "прочность после четырёх верных: {}", card.hp);
        assert_eq!(card.streak, 4);
    }

    #[test]
    fn трёх_ответов_не_хватает() {
        let mut card = обычная();
        let mut moment = START;
        for _ in 0..3 {
            card = review(&card, true, Intensity::Normal, 1.0, moment);
            moment = card.due_at;
        }
        assert!(card.hp > 0, "слово выучилось за три ответа: {}", card.hp);
    }

    #[test]
    fn ошибка_возвращает_карточку_в_ту_же_тренировку() {
        let answered = review(&обычная(), true, Intensity::Normal, 1.0, START);
        let missed = review(&answered, false, Intensity::Normal, 1.0, START);

        assert_eq!(missed.streak, 0, "серия не сброшена");
        assert!(missed.hp > answered.hp, "ошибка не вернула прочность");
        assert!(
            missed.due_at - START <= 15 * MINUTE_MS,
            "карточка вернётся только через {} минут",
            (missed.due_at - START) / MINUTE_MS
        );
    }

    #[test]
    fn интенсивность_меняет_сроки_но_не_требования() {
        let gentle = review(&обычная(), true, Intensity::Gentle, 1.0, START);
        let extreme = review(&обычная(), true, Intensity::Extreme, 1.0, START);

        assert!(
            gentle.due_at > extreme.due_at,
            "лёгкий режим спрашивает не позже экстрима"
        );
        // Главное: прочность снимается одинаково. Иначе «лёгкий» значил бы
        // «выучил хуже», и сравнить свои двести слов с чужими двумястами
        // стало бы невозможно.
        assert_eq!(gentle.hp, extreme.hp);
    }

    #[test]
    fn сроки_растут_от_ответа_к_ответу() {
        let mut card = обычная();
        let mut moment = START;
        let mut steps = Vec::new();
        for _ in 0..5 {
            card = review(&card, true, Intensity::Normal, 1.0, moment);
            steps.push(card.due_at - moment);
            moment = card.due_at;
        }
        let mut sorted = steps.clone();
        sorted.sort_unstable();
        assert_eq!(sorted, steps, "лесенка не растёт: {steps:?}");
    }

    #[test]
    fn вероятность_вспомнить_к_сроку_около_девяноста_процентов() {
        let reviewed = review(&обычная(), true, Intensity::Normal, 1.0, START);
        let at_due = retention(&reviewed, reviewed.due_at);

        assert!(
            (0.87..=0.93).contains(&at_due),
            "к назначенному сроку помнится {at_due} вместо {TARGET_RECALL}"
        );
        assert!(
            retention(&reviewed, reviewed.due_at + 30 * DAY_LEN) < at_due,
            "через месяц помнится не хуже, чем в срок"
        );
    }

    #[test]
    fn непросмотренная_карточка_не_считается_забытой() {
        let mut card = обычная();
        card.due_at = 0;
        assert_eq!(retention(&card, START), 0.0);
        assert_eq!(half_forgotten_at(&card), None);
    }

    #[test]
    fn напоминание_молчит_когда_повторять_нечего() {
        assert_eq!(reminder_at(&[], Intensity::Normal, START, MSK), None);
    }

    #[test]
    fn напоминание_приходит_в_приличное_время() {
        let cards: Vec<Card> = (1..=20)
            .map(|n| {
                review(
                    &карточка(&n.to_string(), FULL_HP),
                    true,
                    Intensity::Normal,
                    1.0,
                    START,
                )
            })
            .collect();
        let at = reminder_at(&cards, Intensity::Normal, START, MSK).expect("напоминание не назначено");

        let hour = local_hour(at, MSK);
        assert!((9..=21).contains(&hour), "напоминание назначено на {hour} часов");
        assert!(at >= START, "напоминание назначено в прошлое");
    }

    #[test]
    fn маленькая_колода_тоже_напомнит_о_себе() {
        // С тремя карточками ждать восьмой означало бы не напомнить никогда.
        let cards: Vec<Card> = (1..=3)
            .map(|n| {
                review(
                    &карточка(&n.to_string(), FULL_HP),
                    true,
                    Intensity::Normal,
                    1.0,
                    START,
                )
            })
            .collect();
        assert!(reminder_at(&cards, Intensity::Normal, START, MSK).is_some());
    }

    #[test]
    fn поправка_не_считается_по_десятку_ответов() {
        assert_eq!(ease(10, 10), 1.0);
        assert!(ease(100, 100) > 1.0, "отличник повторяет лишнее");
        assert!(ease(100, 50) < 1.0, "отстающий не успевает закрепить");
    }

    #[test]
    fn выученные_и_созревшие_считаются_по_разному() {
        let mut learned_card = карточка("a", 0);
        learned_card.due_at = START + 100 * DAY_LEN;
        let mut ripe = карточка("b", FULL_HP);
        ripe.due_at = START - DAY_LEN;
        let mut later = карточка("c", FULL_HP);
        later.due_at = START + DAY_LEN;

        let all = [learned_card, ripe, later];
        assert_eq!(
            learned(&all).iter().map(|c| c.id.clone()).collect::<Vec<_>>(),
            vec!["a"]
        );
        assert_eq!(
            due(&all, START).iter().map(|c| c.id.clone()).collect::<Vec<_>>(),
            vec!["b"]
        );
    }

    /// Западные пояса — то место, где наивная реализация местного часа врёт.
    ///
    /// Деление отрицательного числа в Rust округляет к нулю, а не вниз, и
    /// час до полуночи по Гринвичу превращался бы в отрицательный. Тест
    /// стоит здесь потому, что в Kotlin этого кода не было вовсе: там час
    /// спрашивали у системы, а ядро считает его само.
    #[test]
    fn местный_час_верен_и_к_западу_от_гринвича() {
        // 1 января 1970, 02:00 UTC.
        let at = 2 * HOUR_MS;
        assert_eq!(local_hour(at, 0), 2);
        // Нью-Йорк: −5 часов, то есть 31 декабря 1969, 21:00 — вчерашний
        // день и, значит, отрицательное число часов до вычета остатка.
        assert_eq!(local_hour(at, -300), 21);
        // Девять вечера читателя не разбудит, и трогать этот срок незачем.
        assert_eq!(waking(at, -300), at);

        // А вот четыре утра в Нью-Йорке — это девять утра по Гринвичу.
        let ночь = 9 * HOUR_MS;
        assert_eq!(local_hour(ночь, -300), 4);
        let утро = waking(ночь, -300);
        assert_eq!(local_hour(утро, -300), WAKE);
        assert!(утро > ночь, "напоминание сдвинулось в прошлое");
    }
}

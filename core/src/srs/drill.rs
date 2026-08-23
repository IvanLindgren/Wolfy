//! Задания тренировки.

use super::card::Card;
use crate::grammar::{Exercise, Task};
use serde::{Deserialize, Serialize};

/// Три колоды хаба повторений.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum Deck {
    Words,
    Phrases,
    Rules,
}

impl Deck {
    /// Значение `kind` карточки — по нему колода и набирается.
    ///
    /// Имя хранится строкой в самой карточке и уезжает на сервер, поэтому оно
    /// задано здесь рядом с колодой, а не выводится из имени варианта:
    /// переименовать `Words` в коде можно, а переименовать `word` в чужой
    /// базе — уже нет.
    pub fn kind(self) -> &'static str {
        match self {
            Deck::Words => "word",
            Deck::Phrases => "phrase",
            Deck::Rules => "rule",
        }
    }

    /// Все три по порядку — так они и стоят на экране.
    pub const ALL: [Deck; 3] = [Deck::Words, Deck::Phrases, Deck::Rules];
}

/// Каким способом спрашивают.
///
/// Способы стоят не рядом, а друг за другом: сперва узнать, потом собрать,
/// потом вспомнить с нуля. Узнавание — самое лёгкое, и начинать с ввода по
/// памяти значит требовать от читателя того, чего он ещё не умеет; а
/// заканчивать узнаванием значит не проверить ничего, потому что выбрать
/// верный перевод из четырёх можно, не зная слова.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum DrillKind {
    /// Выбрать один вариант из четырёх.
    Choice,
    /// Собрать слово из букв, часть которых уже стоит на месте.
    Letters,
    /// Ввести слово по памяти.
    Typing,
    /// Собрать фразу из блоков.
    Builder,
    /// Поставить форму в пропуск.
    Gap,
}

/// Одно задание тренировки.
///
/// Плоская запись без наследования: у пяти способов спросить общего гораздо
/// больше, чем различного, — вопрос, ответ и набор кусочков есть у каждого, —
/// а пять типов заставили бы экран разбирать их вместо того, чтобы просто
/// нарисовать.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Drill {
    /// Карточка, к которой относится ответ.
    pub card_id: String,
    pub kind: DrillKind,
    /// Что показывают крупно: перевод слова, русская фраза, название правила.
    pub question: String,
    /// Строка помельче под вопросом: предложение из книги с пропуском на
    /// месте слова, пример правила, подпись «соберите слово».
    #[serde(default)]
    pub subject: String,
    /// С чем сверяют ответ.
    pub answer: String,
    /// Варианты, буквы или блоки — смотря что за способ.
    #[serde(default)]
    pub pieces: Vec<String>,
    /// Буквы, открытые заранее, — номера позиций в [`Self::answer`].
    ///
    /// Открывать часть букв не поблажка: слово из двенадцати букв, собранное
    /// по одной, превращается в головоломку про перебор, а проверяется в ней
    /// терпение, а не память.
    #[serde(default)]
    pub given: Vec<usize>,
    /// Правило, если задание грамматическое.
    #[serde(default)]
    pub rule: String,
    #[serde(default)]
    pub formula: String,
    /// Что показать после ответа.
    #[serde(default)]
    pub explanation: String,
}

/// Ниже этой прочности слово уже узнают — пора собирать его самому.
const ASSEMBLE_BELOW: i32 = 75;

/// А ниже этой — вспоминать с нуля.
const RECALL_BELOW: i32 = 45;

/// Сколько букв держать открытыми, долей от длины слова.
const REVEALED: f32 = 0.55;

/// Размер поля букв в макете — четыре в ряд.
const POOL: usize = 12;

/// Буквы для лишних плиток — по убыванию частоты в английском.
const FILLERS: &str = "etaoinshrdlcumwfgypbvk";

/// Задание по слову.
///
/// Способ выбирается по прочности карточки: пока она высокая, слово узнают,
/// дальше собирают, под конец вспоминают. Выбор из четырёх возможен, только
/// если в колоде есть чужие переводы, — придумывать правдоподобно неверный
/// перевод приложению нечем, а «дом / стол / бегать» рядом с «библиотека» не
/// проверяют ничего.
pub fn for_word(card: &Card, deck: &[Card], seed: i32) -> Drill {
    let prompt = non_blank(&card.translation).unwrap_or(&card.lemma).to_string();
    let sentence = blanked(card);

    let mut others: Vec<String> = Vec::new();
    for other in deck {
        if other.id == card.id || other.deleted {
            continue;
        }
        let Some(translation) = non_blank(&other.translation) else {
            continue;
        };
        if !others.iter().any(|seen| seen == translation) {
            others.push(translation.to_string());
        }
    }

    let choice = card.hp >= ASSEMBLE_BELOW && !card.translation.trim().is_empty() && others.len() >= 3;

    if choice {
        let mut options = vec![card.translation.clone()];
        options.extend(others.into_iter().take(3));
        return Drill {
            card_id: card.id.clone(),
            kind: DrillKind::Choice,
            question: non_blank(&card.surface).unwrap_or(&card.lemma).to_string(),
            subject: sentence,
            answer: card.translation.clone(),
            pieces: shuffled(options, seed),
            given: Vec::new(),
            rule: String::new(),
            formula: String::new(),
            explanation: String::new(),
        };
    }

    if card.hp >= RECALL_BELOW {
        let word = card.lemma.clone();
        let given = revealed(&word, seed);
        let pieces = pool(&word, &given, seed);
        return Drill {
            card_id: card.id.clone(),
            kind: DrillKind::Letters,
            question: prompt,
            subject: sentence,
            answer: word,
            pieces,
            given,
            rule: String::new(),
            formula: String::new(),
            explanation: String::new(),
        };
    }

    Drill {
        card_id: card.id.clone(),
        kind: DrillKind::Typing,
        question: prompt,
        subject: sentence,
        answer: card.lemma.clone(),
        pieces: Vec::new(),
        given: Vec::new(),
        rule: String::new(),
        formula: String::new(),
        explanation: String::new(),
    }
}

/// Задание по фразе: собрать английскую из блоков по русской.
///
/// Блоки режет [`super::chunks::split`] — по глагольным цепочкам и служебным
/// словам, а не по одному слову: «have been reading» это одна мысль, и
/// рассыпать её на три плитки значит превратить упражнение о времени в
/// упражнение о порядке слов.
pub fn for_phrase(card: &Card, blocks: &[String], extra: &[String], seed: i32) -> Drill {
    // Пока фраза крепкая, спрашивают одно слово: предлог или артикль, на
    // которых держится смысл. Собирать всё предложение целиком в этот момент
    // рано — так же, как рано вводить слово по памяти, пока его ещё узнают из
    // четырёх вариантов.
    if card.hp >= ASSEMBLE_BELOW {
        if let Some(found) = marker(&card.surface, seed) {
            return gap_drill(card, &found, seed);
        }
    }

    let mut pieces: Vec<String> = blocks.to_vec();
    pieces.extend(extra.iter().take(3).cloned());

    Drill {
        card_id: card.id.clone(),
        kind: DrillKind::Builder,
        question: non_blank(&card.translation)
            .unwrap_or("Соберите фразу")
            .to_string(),
        subject: String::new(),
        answer: card.surface.clone(),
        pieces: shuffled(pieces, seed),
        given: Vec::new(),
        rule: String::new(),
        formula: String::new(),
        explanation: String::new(),
    }
}

/// Служебные слова, годные в пропуск, — группами по смыслу.
///
/// Варианты берутся из одной группы, и это главное. Выбор между «for» и «the»
/// не спрашивает ни о чём: неверный вариант виден, не читая фразы. Выбор
/// между «for» и «since» — настоящий, и промахиваются в нём ровно там, где
/// промахиваются в жизни.
///
/// Формы глагола сюда не входят намеренно: правдоподобно неверную форму без
/// словаря спряжений не сделать, а спрашивать «has read / have read» наугад —
/// значит когда-нибудь засчитать верный ответ за ошибку. Формы тренирует
/// колода грамматики, где неверные варианты выверены тестом.
const MARKERS: [&[&str]; 6] = [
    &["a", "an", "the"],
    &["in", "on", "at", "to"],
    &["for", "since", "during", "until"],
    &["with", "without", "by", "from"],
    &["of", "about", "over", "under"],
    &["some", "any", "every", "no"],
];

/// Найденный пропуск: слово, его место в предложении и группа вариантов.
///
/// Место — в символах, а не в байтах: предложение приходит из книги и вполне
/// может содержать типографские кавычки и тире.
struct Marker {
    word: String,
    at: std::ops::Range<usize>,
    group: &'static [&'static str],
}

fn gap_drill(card: &Card, found: &Marker, seed: i32) -> Drill {
    let chars: Vec<char> = card.surface.chars().collect();
    let mut gapped: String = chars[..found.at.start].iter().collect();
    gapped.push_str("___");
    gapped.extend(chars[found.at.end..].iter());

    let mut options = vec![found.word.clone()];
    options.extend(
        found
            .group
            .iter()
            .filter(|option| !option.eq_ignore_ascii_case(&found.word))
            .map(|option| option.to_string()),
    );
    options.truncate(4);

    Drill {
        card_id: card.id.clone(),
        kind: DrillKind::Gap,
        question: non_blank(&card.translation)
            .unwrap_or("Какое слово пропущено?")
            .to_string(),
        subject: gapped,
        answer: found.word.clone(),
        pieces: shuffled(options, seed),
        given: Vec::new(),
        rule: String::new(),
        formula: String::new(),
        explanation: String::new(),
    }
}

/// Ищет во фразе слово для пропуска.
///
/// Берётся не первое подходящее, а выбранное по фразе: одно и то же
/// предложение обязано спрашивать об одном и том же, иначе читатель
/// запоминает не язык, а то, что «здесь всегда первый пропуск».
fn marker(sentence: &str, seed: i32) -> Option<Marker> {
    let chars: Vec<char> = sentence.chars().collect();
    let mut found: Vec<Marker> = Vec::new();
    let mut at = 0usize;

    while at < chars.len() {
        if !chars[at].is_alphabetic() {
            at += 1;
            continue;
        }
        let mut end = at;
        while end < chars.len() && (chars[end].is_alphabetic() || chars[end] == '\'') {
            end += 1;
        }
        let word: String = chars[at..end].iter().collect();
        let group = MARKERS
            .iter()
            .find(|group| group.iter().any(|option| option.eq_ignore_ascii_case(&word)));

        // Слово в начале фразы не берём: пропуск первым словом съедает
        // заглавную букву и подсказывает ответ формой строки. Группа из трёх —
        // это артикли, и трёх вариантов там достаточно: четвёртый пришлось бы
        // взять из чужой группы, а он виден, не читая фразы.
        if let Some(group) = group {
            if group.len() >= 3 && at > 0 {
                found.push(Marker {
                    word,
                    at: at..end,
                    group,
                });
            }
        }
        at = end + 1;
    }

    if found.is_empty() {
        return None;
    }
    let pick = Lcg::new(seed).next(found.len());
    Some(found.swap_remove(pick))
}

/// Задание по правилу — целиком из грамматики.
pub fn for_rule(exercise: &Exercise, card_id: &str) -> Drill {
    Drill {
        card_id: card_id.to_string(),
        kind: if exercise.task == Task::Form {
            DrillKind::Gap
        } else {
            DrillKind::Choice
        },
        question: non_blank(exercise.question)
            .unwrap_or("Что здесь за правило?")
            .to_string(),
        subject: exercise.sentence.clone(),
        answer: exercise
            .options
            .get(exercise.answer)
            .cloned()
            .unwrap_or_default(),
        pieces: exercise.options.clone(),
        given: Vec::new(),
        rule: exercise.rule.to_string(),
        formula: exercise.formula.to_string(),
        explanation: exercise.explanation.clone(),
    }
}

/// Предложение из книги с пропуском на месте слова.
///
/// Без пропуска предложение выдало бы ответ: читатель собирает слово из букв,
/// а оно стоит строкой выше.
pub fn blanked(card: &Card) -> String {
    let sentence = card.context.trim();
    if sentence.is_empty() {
        return String::new();
    }

    let target = non_blank(&card.surface).unwrap_or(&card.lemma);
    let chars: Vec<char> = sentence.chars().collect();
    let needle: Vec<char> = target.to_lowercase().chars().collect();
    // Длина после приведения к нижнему регистру обязана совпасть с исходной,
    // иначе позиции разойдутся: у «ß» и подобных регистр меняет длину. Такое
    // предложение оставляем как есть — соврать пропуском хуже, чем не ставить
    // его вовсе.
    if needle.is_empty() || needle.len() > chars.len() || needle.len() != target.chars().count() {
        return sentence.to_string();
    }

    let haystack: Vec<char> = sentence.to_lowercase().chars().collect();
    if haystack.len() != chars.len() {
        return sentence.to_string();
    }

    let at = haystack
        .windows(needle.len())
        .position(|window| window == needle.as_slice());
    let Some(at) = at else {
        return sentence.to_string();
    };

    let mut out: String = chars[..at].iter().collect();
    out.push('…');
    out.extend(chars[at + needle.len()..].iter());
    out
}

/// Какие буквы стоят на месте с самого начала.
fn revealed(word: &str, seed: i32) -> Vec<usize> {
    let length = word.chars().count();
    if length <= 2 {
        return Vec::new();
    }

    let hide = ((length as f32 * (1.0 - REVEALED)) as usize).clamp(2, 6);
    let mut random = Lcg::new(seed);
    let mut positions: Vec<usize> = (0..length).collect();
    // Перемешиваем и прячем первые: так спрятанные буквы разбросаны по слову,
    // а не собраны в хвосте.
    for i in (0..positions.len()).rev() {
        let j = random.next(i + 1);
        positions.swap(i, j);
    }
    let hidden: Vec<usize> = positions.into_iter().take(hide).collect();
    (0..length).filter(|at| !hidden.contains(at)).collect()
}

/// Поле букв: спрятанные вперемешку с лишними.
fn pool(word: &str, given: &[usize], seed: i32) -> Vec<String> {
    let letters: Vec<char> = word.chars().collect();
    let mut pieces: Vec<String> = letters
        .iter()
        .enumerate()
        .filter(|(at, _)| !given.contains(at))
        .map(|(_, letter)| letter.to_string())
        .collect();

    let fillers: Vec<char> = FILLERS.chars().collect();
    let mut random = Lcg::new(seed.wrapping_add(1));
    while pieces.len() < POOL {
        pieces.push(fillers[random.next(fillers.len())].to_string());
    }
    shuffled(pieces, seed.wrapping_add(2))
}

/// Перемешивает одинаково при каждом показе.
///
/// Не случайно: то же задание при повторе обязано выглядеть так же, иначе
/// читатель запоминает не слово, а расположение плиток — и «вспоминает» его
/// ровно до первой перестановки.
pub fn shuffled<T>(items: Vec<T>, seed: i32) -> Vec<T> {
    let mut out = items;
    let mut random = Lcg::new(seed);
    for i in (0..out.len()).rev() {
        let j = random.next(i + 1);
        out.swap(i, j);
    }
    out
}

/// Зерно по умолчанию — из номера карточки.
///
/// Хеш считается тем же способом, что `String.hashCode` в Kotlin: 31 в
/// основании и переполнение по кругу в 32 битах. Своё, а не библиотечное,
/// по той же причине, что и [`Lcg`] — перемешивание обязано совпадать на
/// телефоне и на компьютере, а гарантии стандартных хешей на это не
/// распространяются.
pub fn seed_of(id: &str) -> i32 {
    let mut hash: i32 = 0;
    for unit in id.encode_utf16() {
        hash = hash.wrapping_mul(31).wrapping_add(unit as i32);
    }
    hash
}

fn non_blank(text: &str) -> Option<&str> {
    if text.trim().is_empty() {
        None
    } else {
        Some(text)
    }
}

/// Линейный конгруэнтный генератор.
///
/// Свой, а не библиотечный: перемешивание обязано повторяться от запуска к
/// запуску и одинаково на телефоне и на компьютере, а гарантии стандартных
/// генераторов на это не распространяются. Числа те же, что были в клиенте
/// на Kotlin, — задания при переезде не должны перетасоваться.
struct Lcg {
    state: i32,
}

impl Lcg {
    fn new(seed: i32) -> Lcg {
        Lcg {
            state: if seed == 0 { 1 } else { seed },
        }
    }

    /// Число от нуля до `bound` не включая.
    fn next(&mut self, bound: usize) -> usize {
        if bound <= 1 {
            return 0;
        }
        self.state = self.state.wrapping_mul(1_103_515_245).wrapping_add(12_345);
        (((self.state as u32) >> 16) & 0x7FFF) as usize % bound
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn слово(id: &str, lemma: &str, hp: i32, translation: &str) -> Card {
        let mut card = Card::new(id, lemma, lemma);
        card.translation = translation.to_string();
        card.context = "She left the library at dusk.".to_string();
        card.hp = hp;
        card
    }

    fn обычное(hp: i32) -> Card {
        слово("1", "library", hp, "библиотека")
    }

    fn колода() -> Vec<Card> {
        vec![
            слово("2", "dusk", 100, "сумерки"),
            слово("3", "shelf", 100, "полка"),
            слово("4", "candle", 100, "свеча"),
        ]
    }

    fn зерно(card: &Card) -> i32 {
        seed_of(&card.id)
    }

    fn задание(card: &Card, deck: &[Card]) -> Drill {
        for_word(card, deck, зерно(card))
    }

    #[test]
    fn способ_спросить_растёт_вместе_с_прочностью() {
        let deck = колода();
        assert_eq!(задание(&обычное(90), &deck).kind, DrillKind::Choice);
        assert_eq!(задание(&обычное(60), &deck).kind, DrillKind::Letters);
        assert_eq!(задание(&обычное(10), &deck).kind, DrillKind::Typing);
    }

    #[test]
    fn выбор_из_четырёх_невозможен_без_чужих_переводов() {
        // Придумать правдоподобно неверный перевод приложению нечем, а «дом /
        // стол / бегать» рядом с «библиотека» не проверяют ничего.
        let одиночка = задание(&обычное(100), &[обычное(100)]);
        assert_eq!(одиночка.kind, DrillKind::Letters);
    }

    #[test]
    fn задание_не_прыгает_между_показами() {
        let deck = колода();
        let first = задание(&обычное(60), &deck);
        let second = задание(&обычное(60), &deck);
        assert_eq!(first.pieces, second.pieces);
        assert_eq!(first.given, second.given);
    }

    #[test]
    fn открытых_букв_меньше_чем_слово() {
        let drill = задание(&обычное(60), &колода());
        let буквы: Vec<char> = drill.answer.chars().collect();
        assert!(!drill.given.is_empty(), "не открыто ни одной буквы");
        assert!(drill.given.len() < буквы.len(), "открыто всё слово");

        // Скрытые буквы обязаны найтись в банке — иначе слово не собрать.
        let mut банк = drill.pieces.clone();
        for (at, letter) in буквы.iter().enumerate() {
            if drill.given.contains(&at) {
                continue;
            }
            let плитка = letter.to_string();
            let место = банк.iter().position(|item| *item == плитка);
            match место {
                Some(место) => {
                    банк.remove(место);
                }
                None => panic!("буквы «{letter}» нет в банке: {:?}", drill.pieces),
            }
        }
    }

    #[test]
    fn предложение_из_книги_не_выдаёт_ответ() {
        let drill = задание(&обычное(60), &колода());
        assert!(
            !drill.subject.to_lowercase().contains("library"),
            "слово осталось в предложении: {}",
            drill.subject
        );
        assert!(drill.subject.contains('\u{2026}'), "пропуска в предложении нет");
    }

    #[test]
    fn грамматическое_задание_берётся_из_ядра_целиком() {
        let все = crate::grammar::exercises(crate::lexicon::Lexicon::embedded());
        let exercise = все
            .iter()
            .find(|item| item.task == Task::Form)
            .expect("в справочнике нет заданий на форму");

        let drill = for_rule(exercise, "rule-1");
        assert_eq!(drill.kind, DrillKind::Gap);
        assert_eq!(drill.pieces, exercise.options);
        assert_eq!(drill.answer, exercise.options[exercise.answer]);
        assert_eq!(drill.explanation, exercise.explanation);
        assert_eq!(drill.rule, exercise.rule);
    }

    fn фраза(hp: i32) -> Card {
        let text = "I have been reading this book for a month.";
        let mut card = Card::new("p1", text, text);
        card.kind = "phrase".to_string();
        card.translation = "Я читаю эту книгу уже месяц.".to_string();
        card.context = text.to_string();
        card.hp = hp;
        card
    }

    fn блоки() -> Vec<String> {
        ["I", "have been reading", "this book", "for a month"]
            .iter()
            .map(|s| s.to_string())
            .collect()
    }

    #[test]
    fn крепкую_фразу_спрашивают_пропуском_а_слабую_конструктором() {
        let крепкая = фраза(100);
        let gap = for_phrase(&крепкая, &блоки(), &[], зерно(&крепкая));
        assert_eq!(gap.kind, DrillKind::Gap);
        assert!(gap.subject.contains("___"), "пропуска нет: {}", gap.subject);
        assert!(
            gap.pieces.contains(&gap.answer),
            "верного варианта нет среди четырёх"
        );
        let mut уникальные = gap.pieces.clone();
        уникальные.sort();
        уникальные.dedup();
        assert_eq!(уникальные.len(), gap.pieces.len(), "повтор в вариантах");

        let слабая = фраза(40);
        assert_eq!(
            for_phrase(&слабая, &блоки(), &[], зерно(&слабая)).kind,
            DrillKind::Builder
        );
    }

    #[test]
    fn варианты_пропуска_из_одной_смысловой_группы() {
        // Выбор между «for» и «the» не спрашивает ни о чём: неверный вариант
        // виден, не читая фразы.
        let card = фраза(100);
        let gap = for_phrase(&card, &[], &[], зерно(&card));
        assert!(
            MARKERS
                .iter()
                .any(|group| gap.pieces.iter().all(|piece| group.contains(&piece.as_str()))),
            "варианты из разных групп: {:?}",
            gap.pieces
        );
    }

    #[test]
    fn пропуск_во_фразе_не_прыгает() {
        let card = фраза(100);
        let first = for_phrase(&card, &[], &[], зерно(&card));
        let second = for_phrase(&card, &[], &[], зерно(&card));
        assert_eq!(first.subject, second.subject);
        assert_eq!(first.pieces, second.pieces);
    }

    #[test]
    fn фраза_без_служебных_слов_остаётся_конструктором() {
        let mut card = фраза(100);
        card.surface = "She smiled quietly".to_string();
        card.lemma = card.surface.clone();
        let blocks: Vec<String> = ["She", "smiled", "quietly"]
            .iter()
            .map(|s| s.to_string())
            .collect();
        assert_eq!(
            for_phrase(&card, &blocks, &[], зерно(&card)).kind,
            DrillKind::Builder
        );
    }

    #[test]
    fn перемешивание_ничего_не_теряет() {
        let items: Vec<i32> = (1..=10).collect();
        let mixed = shuffled(items.clone(), 42);
        let mut отсортировано = mixed.clone();
        отсортировано.sort_unstable();
        assert_eq!(отсортировано, items);
        assert_eq!(shuffled(items, 42), mixed);
    }

    /// Формула генератора из Kotlin-версии, слово в слово.
    fn kotlin_lcg(seed: i32, bound: usize) -> usize {
        let state = if seed == 0 { 1 } else { seed };
        let state = state.wrapping_mul(1_103_515_245).wrapping_add(12_345);
        (((state as u32) >> 16) & 0x7FFF) as usize % bound
    }

    /// Генератор обязан совпадать с прежним побитово.
    ///
    /// Иначе задания при переезде перетасуются: читатель, собравший слово
    /// вчера, увидит те же буквы в другом порядке и решит, что приложение
    /// сбилось.
    #[test]
    fn генератор_совпадает_с_прежним_и_не_выходит_за_границы() {
        for seed in [-7, 0, 1, 42, 1_000_003, i32::MAX, i32::MIN] {
            let mut random = Lcg::new(seed);
            assert_eq!(random.next(10), kotlin_lcg(seed, 10), "зерно {seed}");
        }

        // Граница в единицу и ноль ничего не выбирает, а не делит на ноль.
        assert_eq!(Lcg::new(5).next(1), 0);
        assert_eq!(Lcg::new(5).next(0), 0);

        let mut random = Lcg::new(123);
        for _ in 0..1_000 {
            assert!(random.next(7) < 7);
        }
    }

    /// Хеш номера карточки — тот же, что `String.hashCode` в Kotlin.
    #[test]
    fn зерно_считается_как_в_kotlin() {
        assert_eq!(seed_of(""), 0);
        assert_eq!(seed_of("a"), 97);
        assert_eq!(seed_of("ab"), 97 * 31 + 98);
        assert_eq!(seed_of("word"), 3_655_434);
    }
}
